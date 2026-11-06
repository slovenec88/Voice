package voice.core.bookorbit.sync

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import voice.core.bookorbit.FIRST_ASSET_ID
import voice.core.bookorbit.MemoryBookRepository
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.SECOND_ASSET_ID
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.CachedBook
import voice.core.bookorbit.account.CachedBooksStore
import voice.core.bookorbit.account.PendingProgress
import voice.core.bookorbit.account.SyncedProgress
import voice.core.bookorbit.account.SyncedProgressStore
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.BookReadStatus
import voice.core.bookorbit.api.BookStatusDetail
import voice.core.bookorbit.api.PlaybackState
import voice.core.bookorbit.api.PlaybackUpdate
import voice.core.bookorbit.api.StatusUpdate
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testBook
import voice.core.bookorbit.testCard
import voice.core.bookorbit.testManifest
import voice.core.data.Book
import voice.core.data.ListeningEvent
import voice.core.data.store.CurrentBookStore
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.playstate.PlayStateManager

class ProgressSyncTest {

  private val account = testAccount()
  private val bookId = testBook().id
  private val accounts = MemoryDataStore<Account?>(account)
  private val snapshots = MemoryDataStore<Map<String, SyncedProgress>>(emptyMap())
  private val cached =
    MemoryDataStore(mapOf(bookId.value to CachedBook(testManifest(), testCard())))
  private val api =
    mockk<BookOrbitApi> {
      var savedStatus = "read"
      coEvery { playbackState(42) } returns null
      coEvery { updatePlaybackState(42, any()) } answers { Response.success(accepted(secondArg())) }
      coEvery { updateStatus(42, any()) } answers
        {
          savedStatus = secondArg<StatusUpdate>().status
          Response.success(BookReadStatus(savedStatus, at(22).plusMillis(5).toString()))
        }
      coEvery { statusDetail(42) } answers
        {
          BookStatusDetail(BookReadStatus(savedStatus, at(22).toString()))
        }
    }
  private val http = mockk<BookOrbitHttp> { every { authenticatedApi(account) } returns api }
  private val player =
    mockk<PlayerController>(relaxUnitFun = true) {
      coEvery { livePlaybackState(any()) } returns null
    }
  private val playState = PlayStateManager()
  private val currentBook = MemoryDataStore<voice.core.data.BookId?>(null)
  private val library = mockk<LibrarySync>()

  @Test
  fun `first import takes the server position without echoing it back`() = runTest {
    val books = MemoryBookRepository(testBook())
    coEvery { api.playbackState(42) } returns state(80_000, at(10), revision = 5)

    sync(books).syncAll(account)

    assertEquals(80_000L, books.get(bookId)!!.position)
    assertEquals(5L, snapshots.data.first()[bookId.value]!!.revision)
    coVerify(exactly = 0) {
      val _ = api.updatePlaybackState(any(), any())
    }
  }

  @Test
  fun `an offline capture survives recreation and retries its original operation`() = runTest {
    val books = MemoryBookRepository(localBook(30_000, at(10)))
    val requests = mutableListOf<PlaybackUpdate>()
    coEvery { api.updatePlaybackState(42, capture(requests)) } returns
      Response.error(503, "{}".toResponseBody()) andThenAnswer
      {
        Response.success(accepted(secondArg()))
      }
    val firstSync = sync(books)
    firstSync.capture(account, bookId, at(10))

    assertFalse(firstSync.push(account, bookId))
    val persisted = assertNotNull(snapshots.data.first()[bookId.value]!!.pending)
    assertTrue(sync(books).push(account, bookId))

    assertEquals(2, requests.size)
    assertEquals(persisted.update.operationId, requests[1].operationId)
    assertEquals(persisted.update.capturedAt, requests[1].capturedAt)
    assertEquals(requests[0], requests[1])
    assertNull(snapshots.data.first()[bookId.value]!!.pending)
    assertEquals(30_000L, snapshots.data.first()[bookId.value]!!.positionMs)
  }

  @Test
  fun `the newer remote position wins while idle even when it is a rewind`() = runTest {
    val books = MemoryBookRepository(localBook(90_000, at(10)))
    baseline(60_000, at(9))
    coEvery { api.playbackState(42) } returns state(20_000, at(11), revision = 2)

    sync(books).syncAll(account)

    assertEquals(20_000L, books.get(bookId)!!.position)
    coVerify(exactly = 0) {
      val _ = api.updatePlaybackState(any(), any())
    }
  }

  @Test
  fun `a deliberate local rewind is not replaced by the farthest position`() = runTest {
    val books = MemoryBookRepository(localBook(20_000, at(10)))
    baseline(80_000, at(9))
    coEvery { api.playbackState(42) } returns state(80_000, at(9), revision = 1)

    sync(books).capture(account, bookId, at(10))
    assertTrue(sync(books).push(account, bookId))

    coVerify(exactly = 1) {
      val _ =
        api.updatePlaybackState(
          42,
          match { it.assetId == FIRST_ASSET_ID && it.positionMs == 20_000L },
        )
    }
    assertEquals(20_000L, books.get(bookId)!!.position)
  }

  @Test
  fun `a background sync never moves the book that is actively playing`() = runTest {
    val books = MemoryBookRepository(localBook(20_000, at(10)))
    baseline(0, at(9))
    currentBook.updateData { bookId }
    playState.playState = PlayStateManager.PlayState.Playing
    coEvery { api.playbackState(42) } returns state(90_000, at(11), revision = 2)

    sync(books).syncAll(account)

    assertEquals(20_000L, books.get(bookId)!!.position)
    verify(exactly = 0) { player.setPosition(any(), any(), any(), any()) }
    coVerify(exactly = 1) {
      val _ = api.updatePlaybackState(42, match { it.positionMs == 20_000L })
    }
  }

  @Test
  fun `an idle loaded player takes remote jumps through OtherDevice`() = runTest {
    val books = MemoryBookRepository(testBook())
    coEvery { player.livePlaybackState(bookId) } returns
      LivePlaybackState(bookId, testBook().chapters[0].id, 0, false, 1F)
    coEvery { api.playbackState(42) } returns state(80_000, at(10), revision = 5)

    sync(books).syncAll(account)

    verify(exactly = 1) {
      player.setPosition(
        time = 20_000,
        id = testBook().chapters[1].id,
        source = ListeningEvent.Source.OtherDevice,
      )
    }
    assertEquals(80_000L, books.get(bookId)!!.position)
  }

  @Test
  fun `the live player overlay supplies the captured position`() = runTest {
    val books = MemoryBookRepository(testBook(30_000))
    coEvery { player.livePlaybackState(bookId) } returns
      LivePlaybackState(bookId, testBook().chapters[0].id, 45_000, true, 1.5F)

    sync(books).capture(account, bookId, at(10))

    assertEquals(45_000L, snapshots.data.first()[bookId.value]!!.pending!!.update.positionMs)
  }

  @Test
  fun `completion sends read status only after the matching position is accepted`() = runTest {
    val books = MemoryBookRepository(localBook(115_000, at(10)))
    baseline(100_000, at(9))
    coEvery { api.playbackState(42) } returns state(100_000, at(9))
    val progress = sync(books)
    progress.capture(account, bookId, at(10))

    assertTrue(progress.push(account, bookId))

    coVerify(exactly = 1) {
      val _ = api.updateStatus(42, StatusUpdate("read"))
    }
    assertTrue(snapshots.data.first()[bookId.value]!!.finished)
  }

  @Test
  fun `a finished book explicitly reset to its start sends unread`() = runTest {
    val books = MemoryBookRepository(localBook(0, at(10)))
    baseline(120_000, at(9), finished = true)
    coEvery { api.playbackState(42) } returns state(120_000, at(9))
    val progress = sync(books)
    progress.capture(account, bookId, at(10))

    assertTrue(progress.push(account, bookId))

    coVerify(exactly = 1) {
      val _ = api.updateStatus(42, StatusUpdate("unread"))
    }
    coVerify(exactly = 1) {
      val _ = api.updatePlaybackState(42, match { it.positionMs == 0L })
    }
    assertFalse(snapshots.data.first()[bookId.value]!!.finished)
  }

  @Test
  fun `a failed status mutation retries without replaying an accepted position`() = runTest {
    val books = MemoryBookRepository(localBook(115_000, at(10)))
    baseline(100_000, at(9))
    coEvery { api.playbackState(42) } returns
      state(100_000, at(9)) andThen
      state(115_000, at(10), revision = 2)
    coEvery { api.updateStatus(42, StatusUpdate("read")) } returns
      Response.error(503, "{}".toResponseBody()) andThen
      Response.success(BookReadStatus("read", at(22).toString()))
    val progress = sync(books)
    progress.capture(account, bookId, at(10))

    assertFalse(progress.push(account, bookId))
    assertTrue(snapshots.data.first()[bookId.value]!!.pending!!.positionAccepted)
    assertTrue(sync(books).push(account, bookId))

    coVerify(exactly = 1) {
      val _ = api.updatePlaybackState(42, any())
    }
    coVerify(exactly = 2) {
      val _ = api.updateStatus(42, StatusUpdate("read"))
    }
    assertNull(snapshots.data.first()[bookId.value]!!.pending)
  }

  @Test
  fun `a lost response after committing a finished position does not lose read status`() = runTest {
    lostPositionResponse(
      position = 115_000,
      baselinePosition = 100_000,
      baselineFinished = false,
      status = "read",
    )
  }

  @Test
  fun `a lost response after committing a reset does not lose unread status`() = runTest {
    lostPositionResponse(
      position = 0,
      baselinePosition = 120_000,
      baselineFinished = true,
      status = "unread",
    )
  }

  @Test
  fun `revision conflicts rebase once while keeping capture identity`() = runTest {
    val books = MemoryBookRepository(localBook(30_000, at(10)))
    baseline(0, at(8))
    coEvery { api.playbackState(42) } returns
      state(0, at(8)) andThen
      state(20_000, at(9), revision = 2)
    val requests = mutableListOf<PlaybackUpdate>()
    coEvery { api.updatePlaybackState(42, capture(requests)) } returns
      Response.error(409, "{}".toResponseBody()) andThenAnswer
      {
        Response.success(accepted(secondArg()))
      }
    val progress = sync(books)
    progress.capture(account, bookId, at(10))

    assertTrue(progress.push(account, bookId))

    assertEquals(listOf(1L, 2L), requests.map { it.baseRevision })
    assertEquals(requests[0].operationId, requests[1].operationId)
    assertEquals(requests[0].capturedAt, requests[1].capturedAt)
  }

  @Test
  fun `repeated conflicts keep pending work rather than looping`() = runTest {
    val books = MemoryBookRepository(localBook(30_000, at(10)))
    coEvery { api.updatePlaybackState(42, any()) } returns
      Response.error(409, "{}".toResponseBody())
    val progress = sync(books)
    progress.capture(account, bookId, at(10))

    assertFalse(progress.push(account, bookId))

    coVerify(exactly = 2) {
      val _ = api.updatePlaybackState(42, any())
    }
    assertNotNull(snapshots.data.first()[bookId.value]!!.pending)
  }

  @Test
  fun `a changed manifest refetches and remaps an unsent position`() = runTest {
    val books = MemoryBookRepository(localBook(75_000, at(10)))
    val oldManifest = testManifest()
    val newAsset =
      oldManifest.assets[0].copy(
        assetId = "aud_cccccccc-3333-4333-8333-333333333333",
        durationMs = 120_000,
      )
    val replacement =
      oldManifest.copy(revision = "b".repeat(64), assets = listOf(newAsset), chapters = emptyList())
    coEvery { library.refreshBook(account, 42) } coAnswers
      {
        cached.updateData { mapOf(bookId.value to CachedBook(replacement, testCard())) }
        replacement
      }
    val requests = mutableListOf<PlaybackUpdate>()
    coEvery { api.updatePlaybackState(42, capture(requests)) } returns
      Response.error(412, "{}".toResponseBody()) andThenAnswer
      {
        Response.success(accepted(secondArg()))
      }
    val progress = sync(books)
    progress.capture(account, bookId, at(10))

    assertTrue(progress.push(account, bookId))

    assertEquals(SECOND_ASSET_ID, requests[0].assetId)
    assertEquals(newAsset.assetId, requests[1].assetId)
    assertEquals(75_000L, requests[1].positionMs)
    assertEquals(replacement.revision, requests[1].manifestRevision)
  }

  @Test
  fun `an acknowledgement of an older capture does not erase a queued newer capture`() = runTest {
    val books = MemoryBookRepository(localBook(30_000, at(10)))
    val progress = sync(books)
    progress.capture(account, bookId, at(10))
    val old = snapshots.data.first()[bookId.value]!!.pending!!
    val newer =
      PendingProgress(
        old.update.copy(
          operationId = "de3d9e53-9dc4-4adc-8a33-c2d7fddad611",
          positionMs = 40_000,
          capturedAt = at(11).toString(),
        ),
        40_000,
        false,
      )
    coEvery { api.updatePlaybackState(42, any()) } coAnswers
      {
        val update = secondArg<PlaybackUpdate>()
        if (update.operationId == old.update.operationId) {
          snapshots.updateData { values ->
            values + (bookId.value to values.getValue(bookId.value).copy(queued = newer))
          }
          Response.success(accepted(update))
        } else {
          Response.error(503, "{}".toResponseBody())
        }
      }

    assertFalse(progress.push(account, bookId))

    assertEquals(
      newer.update.operationId,
      snapshots.data.first()[bookId.value]!!.pending!!.update.operationId,
    )
  }

  @Test
  fun `a failed read retains a durable pending reset`() = runTest {
    val books = MemoryBookRepository(localBook(0, at(10)))
    baseline(120_000, at(9), finished = true)
    val progress = sync(books)
    progress.capture(account, bookId, at(10))
    coEvery { api.playbackState(42) } throws
      HttpException(Response.error<Unit>(401, "{}".toResponseBody()))

    assertFalse(progress.push(account, bookId))

    assertEquals("unread", snapshots.data.first()[bookId.value]!!.pending!!.status)
    assertEquals(0L, books.get(bookId)!!.position)
  }

  @Test
  fun `a response for an account replaced in flight cannot change books or baselines`() = runTest {
    val books = MemoryBookRepository(testBook())
    coEvery { api.playbackState(42) } coAnswers
      {
        accounts.updateData { account.copy(userId = 8) }
        state(80_000, at(10), revision = 5)
      }

    sync(books).syncAll(account)

    assertEquals(0L, books.get(bookId)!!.position)
    assertEquals(emptyMap(), snapshots.data.first())
  }

  @Test
  fun `coalescing an offline completion keeps its read intent`() = runTest {
    val books = MemoryBookRepository(localBook(40_000, at(10)))
    baseline(30_000, at(9))
    val progress = sync(books)
    progress.capture(account, bookId, at(10))
    books.updateBook(bookId) { localBook(120_000, at(11)).content }
    progress.capture(account, bookId, at(11))
    books.updateBook(bookId) { localBook(115_000, at(12)).content }
    progress.capture(account, bookId, at(12))

    assertEquals("read", snapshots.data.first()[bookId.value]!!.queued!!.status)
    assertTrue(sync(books).push(account, bookId))
    coVerify(exactly = 1) {
      val _ = api.updateStatus(42, StatusUpdate("read"))
    }
    assertEquals(115_000L, snapshots.data.first()[bookId.value]!!.positionMs)
  }

  @Test
  fun `coalescing resumed offline playback keeps its reset intent`() = runTest {
    val books = MemoryBookRepository(localBook(115_000, at(10)))
    baseline(100_000, at(9))
    val progress = sync(books)
    progress.capture(account, bookId, at(10))
    books.updateBook(bookId) { localBook(0, at(11)).content }
    progress.capture(account, bookId, at(11))
    books.updateBook(bookId) { localBook(5_000, at(12)).content }
    progress.capture(account, bookId, at(12))

    assertEquals("unread", snapshots.data.first()[bookId.value]!!.queued!!.status)
    assertTrue(sync(books).push(account, bookId))
    coVerify(exactly = 1) {
      val _ = api.updateStatus(42, StatusUpdate("unread"))
    }
    assertEquals(5_000L, snapshots.data.first()[bookId.value]!!.positionMs)
    cached.updateData { values ->
      values.mapValues { (_, value) ->
        value.copy(card = value.card.copy(readStatus = BookReadStatus("unread", at(22).toString())))
      }
    }
    coEvery { api.playbackState(42) } returns state(5_000, at(12), revision = 2)
    sync(books).syncAll(account)
    assertEquals(5_000L, books.get(bookId)!!.position)
    cached.updateData { values ->
      values.mapValues { (_, value) ->
        value.copy(card = value.card.copy(readStatus = BookReadStatus("unread", at(23).toString())))
      }
    }
    sync(books).syncAll(account)
    assertEquals(0L, books.get(bookId)!!.position)
  }

  @Test
  fun `malformed server progress leaves a retryable operation instead of ending the worker`() =
    runTest {
      val books = MemoryBookRepository(localBook(30_000, at(10)))
      coEvery { api.playbackState(42) } throws
        kotlinx.serialization.SerializationException("Malformed response") andThen
        null
      val progress = sync(books)
      progress.capture(account, bookId, at(10))
      val operation = snapshots.data.first()[bookId.value]!!.pending!!.update.operationId

      assertFalse(progress.push(account, bookId))
      assertEquals(operation, snapshots.data.first()[bookId.value]!!.pending!!.update.operationId)
      assertTrue(progress.push(account, bookId))
    }

  private fun sync(books: MemoryBookRepository) =
    ProgressSync(
      http,
      AccountStore(accounts),
      books,
      SyncedProgressStore(snapshots),
      CachedBooksStore(cached),
      CurrentBookStore(currentBook),
      playState,
      player,
      library,
    )

  private suspend fun lostPositionResponse(
    position: Long,
    baselinePosition: Long,
    baselineFinished: Boolean,
    status: String,
  ) {
    val books = MemoryBookRepository(localBook(position, at(10)))
    baseline(baselinePosition, at(9), baselineFinished)
    coEvery { api.playbackState(42) } returns
      state(baselinePosition, at(9)) andThen
      state(position, at(10), revision = 2)
    val requests = mutableListOf<PlaybackUpdate>()
    coEvery { api.updatePlaybackState(42, capture(requests)) } answers
      {
        if (requests.size == 1) throw IOException("response lost after server commit")
        Response.success(accepted(secondArg()))
      }
    val first = sync(books)
    first.capture(account, bookId, at(10))

    assertFalse(first.push(account, bookId))
    assertEquals(status, snapshots.data.first()[bookId.value]!!.pending!!.status)
    assertTrue(sync(books).push(account, bookId))

    coVerify(exactly = 1) {
      val _ = api.updateStatus(42, StatusUpdate(status))
    }
    assertEquals(1, requests.map { it.operationId }.distinct().size)
    assertTrue(requests.all { it.capturedAt == at(10).toString() })
    assertNull(snapshots.data.first()[bookId.value]!!.pending)
  }

  private suspend fun baseline(
    position: Long,
    time: Instant,
    finished: Boolean = false,
  ) {
    snapshots.updateData {
      mapOf(bookId.value to SyncedProgress(position, finished, time.toString(), revision = 1))
    }
  }

  private fun localBook(
    position: Long,
    time: Instant,
  ): Book = testBook(position).update { it.copy(lastPlayedAt = time) }

  private fun at(seconds: Long): Instant =
    Instant.parse("2026-10-30T10:00:00Z").plusSeconds(seconds)

  private fun state(
    position: Long,
    time: Instant,
    revision: Long = 1,
  ): PlaybackState {
    val second = position >= 60_000
    return PlaybackState(
      if (second) SECOND_ASSET_ID else FIRST_ASSET_ID,
      if (second) position - 60_000 else position,
      position / 1200.0,
      position == 120_000L,
      time.toString(),
      revision,
      testManifest().revision,
    )
  }

  private fun accepted(update: PlaybackUpdate) =
    PlaybackState(
      update.assetId,
      update.positionMs,
      capturedAt = update.capturedAt,
      revision = update.baseRevision + 1,
      manifestRevision = update.manifestRevision,
    )
}
