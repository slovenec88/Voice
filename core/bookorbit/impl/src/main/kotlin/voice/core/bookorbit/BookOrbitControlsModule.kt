package voice.core.bookorbit

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import voice.core.audiobookshelf.ServerDownloads
import voice.core.audiobookshelf.ServerLibrary
import voice.core.bookorbit.download.BookOrbitDownloads

@BindingContainer
@ContributesTo(AppScope::class)
object BookOrbitControlsModule {
  @Provides @BookOrbitControls fun library(library: BookOrbitServerLibrary): ServerLibrary = library

  @Provides
  @BookOrbitControls
  fun downloads(downloads: BookOrbitDownloads): ServerDownloads = downloads
}
