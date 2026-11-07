package voice.core.bookorbit

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import voice.core.audiobookshelf.ServerBookEdit
import voice.core.audiobookshelf.ServerEdit
import voice.core.audiobookshelf.ServerLibrary
import voice.core.audiobookshelf.ServerReachability
import voice.core.audiobookshelf.download.BookDownloadState
import voice.core.bookorbit.download.BookOrbitDownloads
import voice.core.bookorbit.sync.BookOrbitSync
import voice.core.data.BookId
import voice.core.data.isBookOrbit
import voice.core.data.repo.BookRepository
import voice.core.server.ServerProvider

@ContributesIntoSet(AppScope::class, binding = binding<ServerProvider>())
@Inject
class BookOrbitServerLibrary
internal constructor(
  private val bookorbitSync: BookOrbitSync,
  override val downloads: BookOrbitDownloads,
  bookRepository: BookRepository,
) : ServerLibrary, ServerProvider {
  override val library: ServerLibrary
    get() = this

  override val scheme: String = "bo"

  override val syncing = bookorbitSync.syncing
  override val reachability = bookorbitSync.reachability

  override val unavailableBooks: Flow<Set<BookId>> =
    combine(
        bookorbitSync.reachability,
        downloads.states,
        bookRepository.flow(),
      ) { reachability, downloadStates, books ->
        if (reachability != ServerReachability.Unreachable) return@combine emptySet()
        books
          .filter { it.id.isBookOrbit && downloadStates[it.id] !is BookDownloadState.Downloaded }
          .map { it.id }
          .toSet()
      }
      .distinctUntilChanged()

  override fun sync(force: Boolean) {
    bookorbitSync.sync(force)
  }

  override suspend fun updateBook(bookId: BookId, edit: ServerBookEdit): ServerEdit =
    ServerEdit.NotAllowed
}
