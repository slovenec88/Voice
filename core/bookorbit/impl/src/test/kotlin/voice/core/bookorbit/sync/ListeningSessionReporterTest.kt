package voice.core.bookorbit.sync

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import voice.core.bookorbit.MemoryBookRepository
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.CachedBook
import voice.core.bookorbit.account.CachedBooksStore
import voice.core.bookorbit.account.PendingSession
import voice.core.bookorbit.account.PendingSessionsStore
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.SessionRequest
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testBook
import voice.core.bookorbit.testCard
import voice.core.bookorbit.testConnectionGate
import voice.core.bookorbit.testManifest
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController

class ListeningSessionReporterTest {

  private val account = testAccount()
  private val book = testBook()
  private val accounts = MemoryDataStore<Account?>(account)
  private val queue = MemoryDataStore<Map<String, PendingSession>>(emptyMap())
  private val cached =
    CachedBooksStore(
      MemoryDataStore(mapOf(book.id.value to CachedBook(testManifest(), testCard()))),
    )
  private val api =
    mockk<BookOrbitApi> {
      coEvery { session(any(), any()) } returns Response.success(Unit)
    }
  private val http = mockk<BookOrbitHttp> { every { authenticatedApi(account) } returns api }
  private val progress = mockk<ProgressSync>()
  private val player = mockk<PlayerController>()
  private val gate = testConnectionGate()

  @Test
  fun `the progress timer runs while playing and stays quiet after the final pause update`() {
    val dispatcher = StandardTestDispatcher()
    Dispatchers.setMain(dispatcher)
    try {
      runTest(dispatcher) {
        val playback = MutableStateFlow<LivePlaybackState?>(live(0))
        every { player.livePlaybackStateFlow() } returns playback
        coEvery { progress.push(account, book.id) } returns true
        reporter().start(backgroundScope)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        coVerify(exactly = 1) {
          val _ = progress.push(account, book.id)
        }

        playback.value = live(12_000, advancing = false)
        runCurrent()
        coVerify(exactly = 2) {
          val _ = progress.push(account, book.id)
        }
        advanceTimeBy(90_000)
        runCurrent()
        coVerify(exactly = 2) {
          val _ = progress.push(account, book.id)
        }
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `listening time excludes pauses and buffering and is independent of speed`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0, speed = 2F))
    record(reporter, 12, live(24_000, advancing = false, speed = 2F))
    record(reporter, 32, live(24_000, advancing = false, speed = 2F))
    record(reporter, 42, live(24_000, speed = 2F))
    record(reporter, 52, live(44_000, speed = 2F))

    reporter.checkpoint()

    val request = queue.data.first().values.single().request
    assertEquals(22L, request.durationSeconds)
    assertEquals("android", request.source)
    assertEquals("listen", request.sessionType)
    assertEquals(36.666666666666664, request.endProgress)
  }

  @Test
  fun `sessions below the servers ten second minimum are not queued`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 9, live(9_000))
    reporter.checkpoint()
    assertEquals(emptyMap(), queue.data.first())

    record(reporter, 10, live(10_000))
    reporter.checkpoint()

    assertEquals(10L, queue.data.first().values.single().request.durationSeconds)
  }

  @Test
  fun `cumulative checkpoints reuse one UUID after an earlier checkpoint was sent`() = runTest {
    val requests = mutableListOf<SessionRequest>()
    coEvery { api.session(any(), capture(requests)) } returns Response.success(Unit)
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(12_000))
    reporter.checkpoint()
    reporter.flushPending(account)
    record(reporter, 30, live(30_000))
    reporter.checkpoint()
    reporter.flushPending(account)

    assertEquals(listOf(12L, 30L), requests.map { it.durationSeconds })
    assertEquals(requests[0].sessionId, requests[1].sessionId)
    assertEquals(requests[0].startedAt, requests[1].startedAt)
    assertEquals(emptyMap(), queue.data.first())
  }

  @Test
  fun `moving between audio files splits sessions using their server file IDs`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(60_000))
    record(reporter, 25, live(73_000))
    reporter.checkpoint()

    val pending = queue.data.first().values.sortedBy { it.fileId }
    assertEquals(listOf(101L, 102L), pending.map { it.fileId })
    assertEquals(listOf(12L, 13L), pending.map { it.request.durationSeconds })
    assertNotEquals(pending[0].request.sessionId, pending[1].request.sessionId)
  }

  @Test
  fun `a failed checkpoint survives reporter recreation and retries the same request`() = runTest {
    val requests = mutableListOf<SessionRequest>()
    coEvery { api.session(any(), capture(requests)) } returns
      Response.error(503, "{}".toResponseBody()) andThen
      Response.success(Unit)
    val firstReporter = reporter()
    record(firstReporter, 0, live(0))
    record(firstReporter, 15, live(15_000))
    firstReporter.checkpoint()

    firstReporter.flushPending(account)
    assertEquals(1, queue.data.first().size)
    reporter().flushPending(account)

    assertEquals(2, requests.size)
    assertEquals(requests[0], requests[1])
    assertEquals(emptyMap(), queue.data.first())
  }

  @Test
  fun `forgetting a signed out playback session retains its queued report`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 15, live(15_000))
    reporter.checkpoint()
    val pending = queue.data.first()
    assertEquals(1, pending.size)

    reporter.forget()
    reporter.checkpoint()

    assertEquals(pending, queue.data.first())
  }

  @Test
  fun `an old acknowledgement cannot erase a larger unsent checkpoint`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(12_000))
    reporter.checkpoint()
    val first = queue.data.first().values.single().request
    coEvery { api.session(any(), any()) } coAnswers
      {
        record(reporter, 20, live(20_000))
        reporter.checkpoint()
        Response.success(Unit)
      }

    reporter.flushPending(account)

    val retained = queue.data.first().values.single().request
    assertEquals(first.sessionId, retained.sessionId)
    assertEquals(20L, retained.durationSeconds)
  }

  @Test
  fun `a session acknowledgement never acknowledges playback position`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(12_000))
    reporter.checkpoint()

    reporter.flushPending(account)

    coVerify(exactly = 0) {
      val _ = progress.push(any(), any())
    }
    coVerify(exactly = 0) { progress.capture(any(), any(), any()) }
  }

  @Test
  fun `a pause longer than ten minutes starts a new session`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(12_000, advancing = false))
    val first = queue.data.first().values.single().request.sessionId
    record(reporter, 613, live(12_000))
    record(reporter, 625, live(24_000))
    reporter.checkpoint()

    val requests = queue.data.first().values.map { it.request }
    assertEquals(2, requests.size)
    assertTrue(requests.any { it.sessionId != first })
  }

  @Test
  fun `pending sessions of another owner are not sent with current credentials`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(12_000))
    reporter.checkpoint()
    accounts.updateData { account.copy(userId = 8) }

    reporter.flushPending(account)

    coVerify(exactly = 0) {
      val _ = api.session(any(), any())
    }
    assertEquals(1, queue.data.first().size)
  }

  @Test
  fun `switching the active provider retains but does not send BookOrbit sessions`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(12_000))
    reporter.checkpoint()
    gate.release("bo")
    assertTrue(gate.claim("abs"))

    reporter.flushPending(account)

    coVerify(exactly = 0) {
      val _ = api.session(any(), any())
    }
    assertEquals(1, queue.data.first().size)
  }

  @Test
  fun `a deleted audio file does not block later listening sessions`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(12_000))
    reporter.checkpoint()
    val entry = queue.data.first().entries.single()
    val next =
      entry.value.copy(
        fileId = 102,
        request = entry.value.request.copy(sessionId = "70c1ce20-c311-44f7-a3f9-cf48a8d1ba5d"),
      )
    queue.updateData { it + ("${book.id.value}/next" to next) }
    coEvery { api.session(101, any()) } returns Response.error(404, "{}".toResponseBody())

    reporter.flushPending(account)

    coVerify(exactly = 1) {
      val _ = api.session(102, next.request)
    }
    assertEquals(emptyMap(), queue.data.first())
  }

  @Test
  fun `revoked file access is retained without blocking other sessions`() = runTest {
    val reporter = reporter()
    record(reporter, 0, live(0))
    record(reporter, 12, live(12_000))
    reporter.checkpoint()
    val first = queue.data.first().values.single()
    val next =
      first.copy(
        fileId = 102,
        request = first.request.copy(sessionId = "70c1ce20-c311-44f7-a3f9-cf48a8d1ba5d"),
      )
    queue.updateData { it + ("${book.id.value}/next" to next) }
    coEvery { api.session(101, any()) } returns Response.error(403, "{}".toResponseBody())

    reporter.flushPending(account)

    coVerify(exactly = 1) {
      val _ = api.session(102, next.request)
    }
    assertEquals(listOf(first), queue.data.first().values.toList())
  }

  private fun reporter() =
    ListeningSessionReporter(
      http,
      AccountStore(accounts),
      PendingSessionsStore(queue),
      cached,
      MemoryBookRepository(book),
      player,
      progress,
      gate,
    )

  private suspend fun record(
    reporter: ListeningSessionReporter,
    seconds: Long,
    live: LivePlaybackState,
  ) {
    reporter.record(
      account,
      live,
      seconds * 1000,
      Instant.parse("2026-10-30T10:00:00Z").plusSeconds(seconds),
    )
  }

  private fun live(
    position: Long,
    advancing: Boolean = true,
    speed: Float = 1F,
  ): LivePlaybackState {
    val second = position >= 60_000
    return LivePlaybackState(
      book.id,
      book.chapters[if (second) 1 else 0].id,
      if (second) position - 60_000 else position,
      true,
      speed,
      advancing,
    )
  }
}
