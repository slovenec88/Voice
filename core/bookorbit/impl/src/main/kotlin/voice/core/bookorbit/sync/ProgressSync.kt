package voice.core.bookorbit.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import java.time.Instant
import kotlin.math.abs
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.CachedBooksStore
import voice.core.bookorbit.account.PendingProgress
import voice.core.bookorbit.account.SyncedProgress
import voice.core.bookorbit.account.SyncedProgressStore
import voice.core.bookorbit.api.AudiobookManifest
import voice.core.bookorbit.api.BookReadStatus
import voice.core.bookorbit.api.PlaybackState
import voice.core.bookorbit.api.PlaybackUpdate
import voice.core.bookorbit.api.StatusUpdate
import voice.core.bookorbit.bookRef
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.trackRef
import voice.core.data.BookId
import voice.core.data.ListeningEvent
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.playback.PlayerController
import voice.core.playback.overlay
import voice.core.playback.playstate.PlayStateManager

@SingleIn(AppScope::class)
@Inject
internal class ProgressSync(
  private val http: BookOrbitHttp,
  private val accountStore: AccountStore,
  private val bookRepository: BookRepository,
  private val syncedProgressStore: SyncedProgressStore,
  private val cachedBooksStore: CachedBooksStore,
  private val currentBookStore: CurrentBookStore,
  private val playStateManager: PlayStateManager,
  private val playerController: PlayerController,
  private val librarySync: LibrarySync,
) {
  private val mutex = Mutex()

  suspend fun syncAll(account: Account) {
    bookRepository
      .all()
      .filter { account.owns(it.id.bookRef) }
      .forEach { book ->
        sync(account, book.id)
      }
  }

  suspend fun capture(
    account: Account,
    bookId: BookId,
    capturedAt: Instant = Instant.now(),
  ) = mutex.withLock {
    captureLocked(account, bookId, capturedAt)
  }

  suspend fun push(
    account: Account,
    bookId: BookId,
  ): Boolean = mutex.withLock {
    val capturedAt =
      if (isPlaying(bookId)) Instant.now()
      else bookRepository.get(bookId)?.content?.lastPlayedAt ?: Instant.now()
    captureLocked(account, bookId, capturedAt)
    reconcile(account, bookId)
  }

  private suspend fun sync(
    account: Account,
    bookId: BookId,
  ) = mutex.withLock {
    val book = bookRepository.get(bookId) ?: return@withLock
    captureLocked(account, bookId, book.content.lastPlayedAt)
    reconcile(account, bookId)
  }

  private suspend fun captureLocked(
    account: Account,
    bookId: BookId,
    capturedAt: Instant,
  ) {
    if (!account.owns(bookId.bookRef) || !account.sameOwner(accountStore.data.first())) return
    val stored = bookRepository.get(bookId) ?: return
    val book = playerController.livePlaybackState(bookId)?.let(stored::overlay) ?: stored
    val manifest = cachedBooksStore.data.first()[bookId.value]?.manifest ?: return
    val snapshot = syncedProgressStore.data.first()[bookId.value] ?: SyncedProgress()
    val last = snapshot.queued ?: snapshot.pending
    val previousPosition = last?.absolutePositionMs ?: snapshot.positionMs
    val previousFinished = last?.finished ?: snapshot.finished
    if (
      abs(book.position - previousPosition) <= POSITION_TOLERANCE_MS &&
        book.isFinished == previousFinished &&
        !(book.position == 0L && previousPosition > 0)
    ) {
      return
    }
    val track = book.content.currentChapter.trackRef ?: return
    val asset = manifest.assets.firstOrNull { it.assetId == track.assetId } ?: return
    val status =
      when {
        book.isFinished && !previousFinished -> "read"
        book.position == 0L && previousPosition > 0 -> "unread"
        else -> snapshot.queued?.status?.takeIf { it == "unread" || book.isFinished }
      }
    val pending =
      PendingProgress(
        PlaybackUpdate(
          asset.assetId,
          book.content.positionInChapter.coerceIn(0, asset.durationMs ?: Long.MAX_VALUE),
          capturedAt.toString(),
          Uuid.random().toString(),
          snapshot.revision,
          manifest.revision,
        ),
        book.position,
        book.isFinished,
        status,
      )
    remember(
      bookId,
      if (snapshot.pending == null) snapshot.copy(pending = pending)
      else snapshot.copy(queued = pending),
    )
  }

  private suspend fun reconcile(
    account: Account,
    bookId: BookId,
  ): Boolean {
    val ref = bookId.bookRef ?: return true
    if (!account.owns(ref) || !account.sameOwner(accountStore.data.first())) return false
    val api = http.authenticatedApi(account)
    try {
      var server = api.playbackState(ref.bookId)
      if (!account.sameOwner(accountStore.data.first())) return false
      var snapshot = syncedProgressStore.data.first()[bookId.value] ?: SyncedProgress()
      if (snapshot.pending == null && snapshot.queued == null) {
        applyServer(bookId, server, cachedBooksStore.data.first()[bookId.value]?.card?.readStatus)
        return true
      }
      repeat(2) {
        snapshot = syncedProgressStore.data.first()[bookId.value] ?: return true
        val pending = snapshot.pending ?: return true
        val serverTime = server?.capturedAt?.let(::instant) ?: Instant.EPOCH
        val status =
          cachedBooksStore.data.first()[bookId.value]?.card?.readStatus?.takeUnless {
            it.updatedAt == snapshot.ownStatusUpdatedAt
          }
        val statusTime =
          status?.takeIf { it.status == "read" || it.status == "unread" }?.updatedAt?.let(::instant)
            ?: Instant.EPOCH
        val matchingCapture = server?.matchesCapture(pending.update) == true
        val newerStatus =
          statusTime > instant(pending.update.capturedAt) && status?.status != pending.status
        if (
          !isPlaying(bookId) &&
            maxOf(serverTime, statusTime) >= instant(pending.update.capturedAt) &&
            ((server?.revision ?: 0) != snapshot.revision ||
              statusTime > instant(snapshot.capturedAt)) &&
            (!matchingCapture || newerStatus)
        ) {
          promote(bookId, snapshot, server)
          if (snapshot.queued == null) applyServer(bookId, server, status)
          return@repeat
        }
        if (pending.positionAccepted) {
          if (!sendStatus(account, bookId, pending)) return false
          promote(bookId, snapshot, server)
          return@repeat
        }
        var manifest = cachedBooksStore.data.first()[bookId.value]?.manifest ?: return false
        var request = remap(pending, manifest).copy(baseRevision = server?.revision ?: 0)
        var response = api.updatePlaybackState(ref.bookId, request)
        if (response.code() == 412) {
          manifest = librarySync.refreshBook(account, ref.bookId)
          request = remap(pending, manifest).copy(baseRevision = server?.revision ?: 0)
          response = api.updatePlaybackState(ref.bookId, request)
        }
        if (response.code() == 409) {
          server = api.playbackState(ref.bookId)
          if (!account.sameOwner(accountStore.data.first())) return false
          if (
            !isPlaying(bookId) &&
              server != null &&
              instant(server.capturedAt) >= instant(pending.update.capturedAt) &&
              (!server.matchesCapture(pending.update) || newerStatus)
          ) {
            promote(bookId, snapshot, server)
            if (snapshot.queued == null) applyServer(bookId, server, status)
            return@repeat
          }
          request = request.copy(baseRevision = server?.revision ?: 0)
          response = api.updatePlaybackState(ref.bookId, request)
        }
        val accepted = response.body()
        if (!response.isSuccessful || accepted == null) return false
        if (!account.sameOwner(accountStore.data.first())) return false
        syncedProgressStore.updateData { values ->
          val current = values[bookId.value] ?: return@updateData values
          if (current.pending?.update?.operationId != pending.update.operationId)
            return@updateData values
          values +
            (bookId.value to
              current.copy(
                revision = accepted.revision,
                pending = pending.copy(positionAccepted = true),
              ))
        }
        if (!sendStatus(account, bookId, pending)) return false
        promote(bookId, snapshot, accepted)
        server = accepted
      }
      return syncedProgressStore.data.first()[bookId.value]?.pending == null
    } catch (_: IOException) {
      return false
    } catch (_: HttpException) {
      return false
    } catch (_: SerializationException) {
      return false
    }
  }

  private suspend fun sendStatus(
    account: Account,
    bookId: BookId,
    pending: PendingProgress,
  ): Boolean {
    val status = pending.status ?: return true
    val response =
      http.authenticatedApi(account).updateStatus(bookId.bookRef!!.bookId, StatusUpdate(status))
    if (!response.isSuccessful || !account.sameOwner(accountStore.data.first())) return false
    val accepted = http.authenticatedApi(account).statusDetail(bookId.bookRef!!.bookId).readStatus
    if (accepted?.status != status || !account.sameOwner(accountStore.data.first())) return false
    syncedProgressStore.updateData { values ->
      val current = values[bookId.value] ?: return@updateData values
      if (current.pending?.update?.operationId != pending.update.operationId)
        return@updateData values
      values + (bookId.value to current.copy(ownStatusUpdatedAt = accepted.updatedAt))
    }
    return true
  }

  private fun remap(
    pending: PendingProgress,
    manifest: AudiobookManifest,
  ): PlaybackUpdate {
    val asset = manifest.assets.firstOrNull { it.assetId == pending.update.assetId }
    if (asset != null) {
      return pending.update.copy(
        positionMs = pending.update.positionMs.coerceIn(0, asset.durationMs ?: Long.MAX_VALUE),
        manifestRevision = manifest.revision,
      )
    }
    var remaining = pending.absolutePositionMs.coerceIn(0, manifest.totalDurationMs)
    val ordered = manifest.assets.sortedBy { it.sequence }
    for ((index, track) in ordered.withIndex()) {
      val duration = track.durationMs ?: throw IOException("Audiobook duration unavailable")
      if (remaining < duration || index == ordered.lastIndex) {
        return pending.update.copy(
          assetId = track.assetId,
          positionMs = remaining.coerceAtMost(duration),
          manifestRevision = manifest.revision,
        )
      }
      remaining -= duration
    }
    throw IOException("Audiobook has no matching asset")
  }

  private suspend fun promote(
    bookId: BookId,
    old: SyncedProgress,
    server: PlaybackState?,
  ) {
    syncedProgressStore.updateData { values ->
      val current = values[bookId.value] ?: return@updateData values
      if (current.pending?.update?.operationId != old.pending?.update?.operationId)
        return@updateData values
      val pending = current.queued
      values +
        (bookId.value to
          current.copy(
            positionMs = old.pending?.absolutePositionMs ?: current.positionMs,
            finished = old.pending?.finished ?: current.finished,
            capturedAt = server?.capturedAt ?: current.capturedAt,
            revision = server?.revision ?: 0,
            pending = pending,
            queued = null,
          ))
    }
  }

  private suspend fun applyServer(
    bookId: BookId,
    server: PlaybackState?,
    status: BookReadStatus?,
  ) {
    if (isPlaying(bookId)) return
    val book = bookRepository.get(bookId) ?: return
    val manifest = cachedBooksStore.data.first()[bookId.value]?.manifest ?: return
    val snapshot = syncedProgressStore.data.first()[bookId.value]
    if (server == null && status == null) return
    val serverTime = server?.capturedAt?.let(::instant) ?: Instant.EPOCH
    val effectiveStatus = status?.takeUnless { it.updatedAt == snapshot?.ownStatusUpdatedAt }
    val statusTime = effectiveStatus?.updatedAt?.let(::instant) ?: Instant.EPOCH
    var position =
      server?.let { value ->
        val ordered = manifest.assets.sortedBy { it.sequence }
        val asset = ordered.firstOrNull { it.assetId == value.assetId } ?: return
        ordered.take(asset.sequence).sumOf { it.durationMs ?: 0 } + value.positionMs
      } ?: book.position
    if (effectiveStatus != null && statusTime >= serverTime) {
      when (effectiveStatus.status) {
        "read" -> if (book.duration - position >= 10_000) position = book.duration
        "unread" -> position = 0
      }
    }
    val time = maxOf(serverTime, statusTime)
    if (snapshot != null && time < instant(snapshot.capturedAt)) return
    val target = book.positionAt(position)
    if (
      abs(book.position - position) > POSITION_TOLERANCE_MS ||
        book.isFinished != (position > 0 && book.duration - position < 10_000)
    ) {
      if (playerController.livePlaybackState(bookId) != null) {
        playerController.setPosition(
          time = target.positionInChapter,
          id = target.chapterId,
          source = ListeningEvent.Source.OtherDevice,
        )
      }
      bookRepository.updateBook(bookId) {
        it.copy(
          currentChapter = target.chapterId,
          positionInChapter = target.positionInChapter,
          lastPlayedAt = maxOf(it.lastPlayedAt, time),
        )
      }
    }
    remember(
      bookId,
      SyncedProgress(
        position,
        position > 0 && book.duration - position < 10_000,
        time.toString(),
        server?.revision ?: 0,
        ownStatusUpdatedAt = snapshot?.ownStatusUpdatedAt,
      ),
    )
  }

  private suspend fun isPlaying(bookId: BookId): Boolean =
    currentBookStore.data.first() == bookId &&
      playStateManager.playState == PlayStateManager.PlayState.Playing

  private suspend fun remember(
    bookId: BookId,
    value: SyncedProgress,
  ) {
    syncedProgressStore.updateData { it + (bookId.value to value) }
  }
}

private const val POSITION_TOLERANCE_MS = 1500

private fun PlaybackState.matchesCapture(update: PlaybackUpdate): Boolean {
  return assetId == update.assetId &&
    positionMs == update.positionMs &&
    instant(capturedAt) == instant(update.capturedAt)
}
