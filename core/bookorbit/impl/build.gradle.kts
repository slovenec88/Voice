plugins {
  id("voice.library")
}

android {
  androidResources { enable = true }
}

dependencies {
  implementation(projects.core.bookorbit.api)
  implementation(projects.core.analytics.api)
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.featureflag)
  implementation(projects.core.initializer)
  implementation(projects.core.playback.api)
  implementation(projects.core.audiobookshelf.api)
  implementation(projects.core.server.api)
  implementation(projects.core.strings)
  implementation(libs.androidxCore)
  implementation(libs.bundles.retrofit)
  implementation(libs.okhttp)
  implementation(libs.serialization.json)
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.datasource.okhttp)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.okhttp.tls)
}
