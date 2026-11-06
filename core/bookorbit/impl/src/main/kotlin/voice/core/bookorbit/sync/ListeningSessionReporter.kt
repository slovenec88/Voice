package voice.core.bookorbit.sync

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.CachedBooksStore
import voice.core.bookorbit.account.PendingSession
import voice.core.bookorbit.account.PendingSessionsStore
import voice.core.bookorbit.api.SessionRequest
import voice.core.bookorbit.bookRef
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.trackRef
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.overlay
import voice.core.server.ServerConnectionGate

@SingleIn(AppScope::class)
@Inject
internal class ListeningSessionReporter(
  private val http: BookOrbitHttp,
  private val accountStore: AccountStore,
  private val pendingSessionsStore: PendingSessionsStore,
  private val cachedBooksStore: CachedBooksStore,
  private val bookRepository: BookRepository,
  private val playerController: PlayerController,
  private val progressSync: ProgressSync,
  private val connectionGate: ServerConnectionGate,
) {
  private data class Session(
    val id: String,
    val account: Account,
    val bookId: BookId,
    val assetId: String,
    val fileId: Long,
    val startedAt: Instant,
    val startProgress: Double,
    var endProgress: Double,
    var activeMs: Long = 0,
    var lastAt: Long,
    var advancing: Boolean,
    var endedAt: Instant,
    var pausedAt: Long? = null,
    var checkpointedSeconds: Long = 0,
  )

  private val mutex = Mutex()
  private val sendMutex = Mutex()
  private var session: Session? = null

  fun start(scope: CoroutineScope) {
    scope.launch(Dispatchers.Main.immediate) {
      combine(accountStore.data, connectionGate.provider) { account, selected ->
          account
            ?.takeIf { selected == "bo" }
            ?.let { "${it.serverUrl}/${it.instanceId}/${it.userId}" }
        }
        .distinctUntilChanged()
        .collectLatest { owner ->
          if (owner == null) {
            record(null, null)
            return@collectLatest
          }
          playerController.livePlaybackStateFlow().collect { live ->
            val account = accountStore.data.first()
            val before = session
            record(account, live)
            if (
              before != null &&
                (!live.isAdvancing() ||
                  before.bookId != live?.bookId ||
                  before.assetId != live.chapterId.trackRef?.assetId)
            ) {
              checkpoint()
              scope.launch {
                flushPending(before.account)
                val _ = progressSync.push(before.account, before.bookId)
              }
            }
          }
        }
    }
    scope.launch {
      while (true) {
        delay(30.seconds)
        checkpoint()
        val account = accountStore.data.first()?.takeUnless { it.needsLogin } ?: continue
        flushPending(account)
        val activeBook = mutex.withLock {
          session?.takeIf { it.advancing && account.sameOwner(it.account) }?.bookId
        }
        if (activeBook != null) {
          val _ = progressSync.push(account, activeBook)
        }
      }
    }
  }

  internal suspend fun record(
    account: Account?,
    live: LivePlaybackState?,
    nowMs: Long = SystemClock.elapsedRealtime(),
    now: Instant = Instant.now(),
  ) = mutex.withLock {
    val previous = session
    if (previous != null) {
      if (previous.advancing) previous.activeMs += (nowMs - previous.lastAt).coerceAtLeast(0)
      previous.lastAt = nowMs
      previous.endedAt = now
      previous.advancing = false
    }
    if (
      account == null ||
        live == null ||
        connectionGate.provider.value != "bo" ||
        !account.owns(live.bookId.bookRef)
    ) {
      persist(previous)
      session = null
      return@withLock
    }
    val stored = bookRepository.get(live.bookId) ?: return@withLock
    val book = stored.overlay(live)
    val track = live.chapterId.trackRef ?: return@withLock
    val manifest = cachedBooksStore.data.first()[live.bookId.value]?.manifest ?: return@withLock
    val asset = manifest.assets.firstOrNull { it.assetId == track.assetId } ?: return@withLock
    val progress = (book.position.toDouble() / book.duration * 100).coerceIn(0.0, 100.0)
    val continued = previous?.takeIf {
      it.account.sameOwner(account) &&
        it.bookId == live.bookId &&
        it.assetId == track.assetId &&
        (it.pausedAt == null || nowMs - it.pausedAt!! < 600_000)
    }
    if (continued == null) {
      persist(previous)
      session =
        if (live.isAdvancing) {
          Session(
            Uuid.random().toString(),
            account,
            live.bookId,
            track.assetId,
            asset.fileId,
            now,
            progress,
            progress,
            lastAt = nowMs,
            advancing = true,
            endedAt = now,
          )
        } else {
          null
        }
    } else {
      continued.advancing = live.isAdvancing
      continued.endProgress = progress
      continued.pausedAt = if (live.isAdvancing) null else continued.pausedAt ?: nowMs
      if (!live.isAdvancing) persist(continued)
    }
  }

  suspend fun checkpoint() = mutex.withLock { persist(session) }

  private suspend fun persist(value: Session?) {
    if (
      value == null ||
        value.activeMs < 10_000 ||
        value.activeMs / 1000 <= value.checkpointedSeconds ||
        !value.account.sameOwner(accountStore.data.first())
    ) {
      return
    }
    val request =
      SessionRequest(
        value.id,
        value.startedAt.toString(),
        value.endedAt.toString(),
        value.activeMs / 1000,
        value.endProgress - value.startProgress,
        value.endProgress,
      )
    val key = "${value.bookId.value}/${value.id}"
    pendingSessionsStore.updateData { queue ->
      val previous = queue[key]
      if (previous != null && previous.request.durationSeconds >= request.durationSeconds) {
        queue
      } else {
        queue + (key to PendingSession(value.fileId, request))
      }
    }
    value.checkpointedSeconds = request.durationSeconds
  }

  suspend fun flushPending(account: Account) = sendMutex.withLock {
    if (connectionGate.provider.value != "bo") return@withLock
    for ((key, pending) in pendingSessionsStore.data.first()) {
      if (!account.sameOwner(accountStore.data.first())) return@withLock
      val book = key.substringBeforeLast('/').let(::BookId)
      if (!account.owns(book.bookRef)) continue
      try {
        val response = http.authenticatedApi(account).session(pending.fileId, pending.request)
        if (response.code() == 403) continue
        if (!response.isSuccessful && response.code() != 404 && response.code() != 410)
          return@withLock
        if (!account.sameOwner(accountStore.data.first())) return@withLock
        pendingSessionsStore.updateData { queue ->
          if (queue[key] == pending) queue - key else queue
        }
      } catch (_: IOException) {
        return@withLock
      } catch (_: HttpException) {
        return@withLock
      }
    }
  }

  suspend fun forget() = mutex.withLock { session = null }
}

private fun LivePlaybackState?.isAdvancing(): Boolean = this?.isAdvancing == true
