package voice.core.audiobookshelf

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import voice.core.audiobookshelf.download.AudiobookshelfDownloads
import voice.core.audiobookshelf.sync.AudiobookshelfServerLibrary

@BindingContainer
@ContributesTo(AppScope::class)
object AudiobookshelfControlsModule {
  @Provides
  @AudiobookshelfControls
  fun library(library: AudiobookshelfServerLibrary): ServerLibrary = library

  @Provides
  @AudiobookshelfControls
  fun downloads(downloads: AudiobookshelfDownloads): ServerDownloads = downloads
}
