package voice.core.server

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import voice.core.audiobookshelf.ServerBookEdit
import voice.core.audiobookshelf.ServerDownloads
import voice.core.audiobookshelf.ServerEdit
import voice.core.audiobookshelf.ServerLibrary
import voice.core.audiobookshelf.ServerReachability
import voice.core.data.BookId

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<ServerLibrary>())
@ContributesBinding(AppScope::class, binding = binding<ServerDownloads>())
@Inject
class CombinedServerLibrary(
  providers: Set<ServerProvider>,
  private val gate: ServerConnectionGate,
) : ServerLibrary, ServerDownloads {
  private val byScheme = providers.associateBy(ServerProvider::scheme)
  private val active
    get() = byScheme[gate.provider.value]

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun <T> selected(default: T, flow: (ServerProvider) -> Flow<T>): Flow<T> =
    gate.provider.map { byScheme[it] }.flatMapLatest { it?.let(flow) ?: flowOf(default) }

  override val syncing =
    selected(false) { it.library.syncing }.stateIn(scope, SharingStarted.Eagerly, false)
  override val reachability =
    selected(ServerReachability.Unknown) { it.library.reachability }
      .stateIn(scope, SharingStarted.Eagerly, ServerReachability.Unknown)
  override val unavailableBooks = selected(emptySet()) { it.library.unavailableBooks }
  override val states = selected(emptyMap()) { it.downloads.states }
  override val books = selected(emptyList()) { it.downloads.books }
  override val usedBytes = selected(0L) { it.downloads.usedBytes }
  override val downloadOverMobileData = selected(false) { it.downloads.downloadOverMobileData }

  private fun provider(bookId: BookId) =
    byScheme[bookId.value.substringBefore(':')]?.takeIf { it === active }

  override fun sync(force: Boolean) {
    active?.library?.sync(force)
  }

  override suspend fun updateBook(bookId: BookId, edit: ServerBookEdit): ServerEdit =
    provider(bookId)?.library?.updateBook(bookId, edit) ?: ServerEdit.NotAllowed

  override suspend fun setDownloadOverMobileData(enabled: Boolean) {
    active?.downloads?.setDownloadOverMobileData(enabled)
  }

  override suspend fun download(bookId: BookId) {
    provider(bookId)?.downloads?.download(bookId)
  }

  override suspend fun remove(bookId: BookId) {
    provider(bookId)?.downloads?.remove(bookId)
  }

  override suspend fun removeAll() {
    active?.downloads?.removeAll()
  }

  override suspend fun shouldAskForNotifications(): Boolean =
    active?.downloads?.shouldAskForNotifications() == true

  override suspend fun onAskedForNotifications() {
    active?.downloads?.onAskedForNotifications()
  }
}
