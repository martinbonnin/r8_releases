#!/usr/bin/env kotlin

//@file:Repository("file://~/.m2/repository/")
@file:Repository("https://repo1.maven.org/maven2/")

@file:DependsOn("com.squareup.okhttp3:okhttp:4.9.0")
@file:DependsOn("org.slf4j:slf4j-simple:1.7.32")
@file:DependsOn("com.github.ajalt.clikt:clikt-jvm:3.2.0")
@file:DependsOn("net.mbonnin.vespene:vespene-lib:0.7.0")
@file:DependsOn("com.squareup.retrofit2:converter-moshi:2.9.0")
@file:DependsOn("org.bouncycastle:bcprov-jdk15on:1.64")
@file:DependsOn("org.bouncycastle:bcpg-jdk15on:1.64")
@file:Suppress("EXPERIMENTAL_API_USAGE")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.runBlocking
import net.mbonnin.vespene.lib.PortalClient
import net.mbonnin.vespene.lib.PublicationType
import net.mbonnin.vespene.lib.md5
import net.mbonnin.vespene.lib.sha1
import net.mbonnin.vespene.sign
import okio.buffer
import okio.source
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * A script that releases an unminified version
 */
check(File("tools/create_maven_release.py").exists()) {
  "r8_release.main.kts needs to be run from the r8 root"
}

//System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE");

object : CliktCommand() {
  val local by option(help = "Deploy to maven local").flag()
  val version by option().help("The version to use in the published artifacts").required()
  val versionToOverWrite by option().help("The version produced by the R8 scripts. If you're on a tag, this shouldn't be required")

  override fun run() {
    withDir { tmpDir ->
      val releaseFile = tmpDir.resolve("r8.zip")
      makeRelease(tmpDir, releaseFile, version, local)
    }
  }

  private fun makeRelease(tmpDir: File, releaseFile: File, version: String, local: Boolean) {
    val outDir = tmpDir.resolve("out")

    execOrDie("tools/create_maven_release.py", "--out", releaseFile.absolutePath)
    execOrDie("unzip", "-d", outDir.absolutePath, releaseFile.absolutePath)

    val actualVersion = versionToOverWrite ?: version
    outDir.walk(direction = FileWalkDirection.BOTTOM_UP).forEach {
      if (it.name.contains(actualVersion)) {
        val newName = it.name.replace(actualVersion, version)
        it.renameTo(File(it.parentFile, newName))
      }
    }
    outDir.walk(direction = FileWalkDirection.BOTTOM_UP).forEach {
      if (it.extension == "pom") {
        it.writeText(
          it.readText()
            .replace(actualVersion, version)
            .replace("<groupId>com.android.tools</groupId>", "<groupId>net.mbonnin.r8</groupId>")
        )
      }
    }
    File(outDir, "net/mbonnin/r8").mkdirs()
    File(outDir, "com/android/tools/r8").copyRecursively(File(outDir, "net/mbonnin/r8/r8"), overwrite = true)
    File(outDir, "com").deleteRecursively()

    val sourcesJar = File(outDir, "net/mbonnin/r8/r8/$version/r8-$version-sources.jar")
    val javadocJar = File(outDir, "net/mbonnin/r8/r8/$version/r8-$version-javadoc.jar")
    createEmptyZip(javadocJar)
    createZip(File(".").resolve("src/main/java"), sourcesJar)

    if (local) {
      val mavenLocal = File("${System.getenv("HOME")}/.m2/")
      mavenLocal.mkdirs()
      outDir.copyRecursively(File(mavenLocal, "repository"), overwrite = true)
    } else {
      val privateKey =
        System.getenv("GPG_PRIVATE_KEY") ?: throw IllegalArgumentException("Please specify GPG_PRIVATE_KEY")
      val privateKeyPassword = System.getenv("GPG_PRIVATE_KEY_PASSWORD")
        ?: throw IllegalArgumentException("Please specify GPG_PRIVATE_KEY_PASSWORD")

      val (artifacts, other) = File(outDir, "net/mbonnin/r8/r8/$version/").listFiles()!!.filter {
        it.isFile
      }.partition { it.extension in listOf("jar", "pom") }

      other.forEach { it.delete() }
      artifacts.forEach { file ->
        File(file.absolutePath + ".md5").writeText(file.source().buffer().md5())
        File(file.absolutePath + ".asc").writeText(file.source().buffer().sign(privateKey, privateKeyPassword))
        File(file.absolutePath + ".sha1").writeText(file.source().buffer().sha1())
      }

      val r8FinalZip = tmpDir.resolve("r8_final.zip")
      createZip(outDir, r8FinalZip)

      val portalClient = PortalClient(
        username = System.getenv("SONATYPE_USERNAME")
          ?: error("Specify SONATYPE_USERNAME or --local to publish locally"),
        password = System.getenv("SONATYPE_PASSWORD")
          ?: error("Specify SONATYPE_PASSWORD or --local to publish locally")
      )

      portalClient.upload(r8FinalZip, "R8 $version", PublicationType.USER_MANAGED)
    }

    println("done")
    // Force exit to kill the OkHttp threadpools
    exitProcess(0)
  }

  private fun createZip(fromDir: File, to: File) {
    ZipOutputStream(to.outputStream()).use { zipOutputStream ->
      fromDir.walk().filter { it.isFile }.forEach { file ->
        zipOutputStream.putNextEntry(ZipEntry(file.relativeTo(fromDir).path))
        file.inputStream().copyTo(zipOutputStream)
        zipOutputStream.closeEntry()
      }
    }
  }
}.main(args)

fun execOrDie(vararg args: String) {
  ProcessBuilder()
    .inheritIO()
    .command(*args)
    .start()
    .waitFor()
    .also {
      check(it == 0) {
        "Cannot execute ${args.joinToString(" ")} (statusCode: $it)"
      }
    }
}

fun withDir(block: (File) -> Unit) {
  val tmpDir = File("tmp")
  tmpDir.deleteRecursively()
  block(tmpDir)
}

fun createEmptyZip(dst: File) {
  val zipOutStream = ZipOutputStream(dst.outputStream())
  zipOutStream.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
  zipOutStream.write("Manifest-Version: 1.0\n\n".toByteArray())
  zipOutStream.closeEntry()
  zipOutStream.close()
}