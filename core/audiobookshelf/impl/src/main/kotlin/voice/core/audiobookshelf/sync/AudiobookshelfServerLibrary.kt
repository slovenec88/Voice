package voice.core.audiobookshelf.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import voice.core.audiobookshelf.ServerBookEdit
import voice.core.audiobookshelf.ServerEdit
import voice.core.audiobookshelf.ServerLibrary
import voice.core.audiobookshelf.ServerReachability
import voice.core.audiobookshelf.download.AudiobookshelfDownloads
import voice.core.audiobookshelf.download.BookDownloadState
import voice.core.data.BookId
import voice.core.data.isAudiobookshelf
import voice.core.data.repo.BookRepository
import voice.core.server.ServerProvider

@ContributesIntoSet(AppScope::class, binding = binding<ServerProvider>())
@Inject
class AudiobookshelfServerLibrary
internal constructor(
  private val audiobookshelfSync: AudiobookshelfSync,
  private val serverBookUpdate: ServerBookUpdate,
  override val downloads: AudiobookshelfDownloads,
  bookRepository: BookRepository,
) : ServerLibrary, ServerProvider {
  override val scheme = "abs"
  override val library: ServerLibrary
    get() = this

  override val syncing: StateFlow<Boolean> = audiobookshelfSync.syncing

  override val reachability: StateFlow<ServerReachability> = audiobookshelfSync.reachability

  override val unavailableBooks: Flow<Set<BookId>> =
    combine(
        audiobookshelfSync.reachability,
        downloads.states,
        bookRepository.flow(),
      ) { reachability, downloadStates, books ->
        if (reachability != ServerReachability.Unreachable) return@combine emptySet()
        books
          .filter {
            it.id.isAudiobookshelf && downloadStates[it.id] !is BookDownloadState.Downloaded
          }
          .map { it.id }
          .toSet()
      }
      .distinctUntilChanged()

  override fun sync(force: Boolean) {
    audiobookshelfSync.sync(force)
  }

  override suspend fun updateBook(
    bookId: BookId,
    edit: ServerBookEdit,
  ): ServerEdit = serverBookUpdate.update(bookId, edit)
}
