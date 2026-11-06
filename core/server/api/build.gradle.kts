plugins {
  id("voice.library")
}

dependencies {
  api(projects.core.data.api)
  api(projects.core.audiobookshelf.api)
  api(libs.media3.exoplayer)
}
