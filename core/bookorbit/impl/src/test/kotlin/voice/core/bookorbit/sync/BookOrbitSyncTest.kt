package voice.core.bookorbit.sync

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.OTHER_INSTANCE_ID
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.api.BoUser
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.ServerIdentity
import voice.core.bookorbit.download.BookOrbitDownloads
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testConnectionGate

class BookOrbitSyncTest {

  private val account = testAccount()
  private val accounts = MemoryDataStore<Account?>(account)
  private val identityCalled = CompletableDeferred<Unit>()
  private val respond = CompletableDeferred<Unit>()
  private val publicApi = mockk<BookOrbitApi>()
  private val authorizedApi = mockk<BookOrbitApi> { coEvery { me() } returns BoUser(7, "listener") }
  private val http =
    mockk<BookOrbitHttp> {
      every { api(account.serverUrl) } returns publicApi
      every { authenticatedApi(account) } returns authorizedApi
    }
  private val library = mockk<LibrarySync>(relaxUnitFun = true)
  private val progress = mockk<ProgressSync>(relaxUnitFun = true)
  private val bookmarks = mockk<BookmarkSync>(relaxUnitFun = true)
  private val sessions = mockk<ListeningSessionReporter>(relaxUnitFun = true)
  private val downloads = mockk<BookOrbitDownloads>(relaxUnitFun = true)

  @Test
  fun `a temporary server identity failure does not invalidate login`() = runTest {
    val sync = sync()
    respondWith(Response.error(503, "{}".toResponseBody()))

    sync.sync(force = true)
    finish(sync)

    assertEquals(account, accounts.data.first())
    coVerify(exactly = 0) {
      val _ = authorizedApi.me()
    }
    coVerify(exactly = 0) { library.sync(any()) }
  }

  @Test
  fun `a rate limited identity request preserves credentials and pending work`() = runTest {
    val sync = sync()
    respondWith(Response.error(429, "{}".toResponseBody()))

    sync.sync(force = true)
    finish(sync)

    assertEquals(account, accounts.data.first())
    coVerify(exactly = 0) { progress.syncAll(any()) }
    coVerify(exactly = 0) { sessions.flushPending(any()) }
  }

  @Test
  fun `a verified different server identity stops authenticated syncing`() = runTest {
    val sync = sync()
    respondWith(Response.success(ServerIdentity("bookorbit", 1, OTHER_INSTANCE_ID)))

    sync.sync(force = true)
    finish(sync)

    assertTrue(accounts.data.first()!!.needsLogin)
    assertEquals(account.accessToken, accounts.data.first()!!.accessToken)
    coVerify(exactly = 0) {
      val _ = authorizedApi.me()
    }
  }

  @Test
  fun `a verified account sync includes library progress bookmarks and previously queued sessions`() =
    runTest {
      val sync = sync()
      respondWith(Response.success(ServerIdentity("bookorbit", 1, account.instanceId)))

      sync.sync(force = true)
      finish(sync)

      coVerify(exactly = 1) { library.sync(account) }
      coVerify(exactly = 1) { downloads.reconcile() }
      coVerify(exactly = 1) { progress.syncAll(account) }
      coVerify(exactly = 1) { bookmarks.sync(account) }
      coVerify(exactly = 1) { sessions.flushPending(account) }
    }

  private fun respondWith(response: Response<ServerIdentity>) {
    coEvery { publicApi.identity() } coAnswers
      {
        identityCalled.complete(Unit)
        respond.await()
        response
      }
  }

  private suspend fun finish(sync: BookOrbitSync) {
    identityCalled.await()
    assertTrue(sync.syncing.value)
    respond.complete(Unit)
    sync.syncing.first { !it }
    sync.cancel()
  }

  private fun sync() =
    BookOrbitSync(
      accountStore = AccountStore(accounts),
      http = http,
      librarySync = library,
      progressSync = progress,
      bookmarkSync = bookmarks,
      downloads = downloads,
      listeningSessionReporter = sessions,
      bookRepository = mockk(),
      contentRepo = mockk(),
      playStateManager = mockk(),
      currentBookStore = mockk(),
      context = mockk(),
      connectionGate = testConnectionGate(),
    )
}
