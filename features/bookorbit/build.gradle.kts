plugins {
  id("voice.library")
  id("voice.compose")
}

dependencies {
  implementation(projects.core.bookorbit.api)
  implementation(projects.core.server.api)
  implementation(projects.core.audiobookshelf.api)
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.ui)
  implementation(projects.core.strings)
  implementation(projects.navigation)
  implementation(libs.androidxCore)
  implementation(libs.coil)
  implementation(libs.navigation3.ui)
}
