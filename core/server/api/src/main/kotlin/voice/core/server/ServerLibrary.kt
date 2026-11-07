package voice.core.server

import androidx.media3.datasource.DataSource
import voice.core.audiobookshelf.ServerDownloads
import voice.core.audiobookshelf.ServerLibrary

interface ServerProvider {
  val scheme: String
  val library: ServerLibrary
  val downloads: ServerDownloads
}

interface RemoteMediaSource {
  val scheme: String
  val factory: DataSource.Factory
}
