[![Maven Central](https://img.shields.io/maven-central/v/com.apollographql.apollo/apollo-api?style=flat-square)](https://central.sonatype.com/namespace/net.mbonnin.r8)

# R8 release

A script that automates publishing an un-minified version of R8 to Maven Central.

To use the un-minified release, replace `com.android.tools` by `net.mbonnin.r8`:

```toml
[libraries]
r8 = { module = "net.mbonnin.r8:r8", version.ref = "r8" }
```

Because the upstream repository doesn't always create tag, the releases are triggered manually when needed. 

If you need a specific version, please [open an issue](https://github.com/martinbonnin/r8_releases/issues/new).  