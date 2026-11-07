plugins {
  id("voice.library")
}

dependencies {
  implementation(projects.core.featureflag)
  implementation(libs.okhttp)
}
