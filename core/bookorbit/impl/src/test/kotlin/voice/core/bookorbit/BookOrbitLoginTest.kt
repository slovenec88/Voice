package voice.core.bookorbit

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.CachedBooksStore
import voice.core.bookorbit.account.PendingProgress
import voice.core.bookorbit.account.PendingSession
import voice.core.bookorbit.account.PendingSessionsStore
import voice.core.bookorbit.account.SyncedBookmarksStore
import voice.core.bookorbit.account.SyncedProgress
import voice.core.bookorbit.account.SyncedProgressStore
import voice.core.bookorbit.api.PlaybackUpdate
import voice.core.bookorbit.api.SessionRequest
import voice.core.bookorbit.download.BookOrbitDownloads
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.data.BookId
import voice.core.data.repo.BookContentRepo
import voice.core.data.store.CurrentBookStore
import voice.core.playback.PlayerController
import voice.core.server.ServerConnectionGate

class BookOrbitLoginTest {

  private val server = MockWebServer()

  @Before
  fun setUp() {
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun `a server is identified before native credentials are submitted`() = runTest {
    enqueueDiscovery()
    server.enqueue(MockResponse(code = 200, body = fixture("native-login")))
    val bookOrbit = bookOrbit()

    val found = assertIs<FindServerResult.Found>(bookOrbit.findServer(server.url("/").toString()))
    val login = assertIs<LoginResult.Success>(bookOrbit.login(found.server, "listener", "password"))

    assertEquals(INSTANCE_ID, login.login.account.instanceId)
    assertEquals(7L, login.login.account.userId)
    assertEquals(5, server.requestCount)
    assertEquals("/api/v1/server-identity", server.takeRequest().url.encodedPath)
    assertEquals("/api/v1/auth/setup-status", server.takeRequest().url.encodedPath)
    assertEquals("/api/v1/auth/login-options", server.takeRequest().url.encodedPath)
    assertEquals("/api/v1/app-info", server.takeRequest().url.encodedPath)
    assertEquals("/api/v1/auth/login", server.takeRequest().url.encodedPath)
  }

  @Test
  fun `cloudflare credentials protect discovery login refresh media and logout over HTTPS`() =
    runTest {
      val access = CloudflareAccess("test-client", "test-secret")
      val accounts = MemoryDataStore<Account?>(null)
      val gate = ServerConnectionGate()
      MockWebServer().use { tlsServer ->
        val http = tlsServer.startTlsClient(accounts, gate)
        val bookOrbit = bookOrbit(accounts, connectionGate = gate, http = http)
        tlsServer.enqueueDiscovery()
        tlsServer.enqueue(MockResponse(code = 200, body = fixture("native-login")))

        val found =
          assertIs<FindServerResult.Found>(
            bookOrbit.findServer(tlsServer.url("/books/").toString(), access),
          )
        val login =
          assertIs<LoginResult.Success>(bookOrbit.login(found.server, "listener", "password"))
        assertTrue(bookOrbit.connect(login.login, listOf("3")))
        assertEquals(access, accounts.data.first()?.cloudflareAccess)

        tlsServer.enqueue(MockResponse(code = 401))
        tlsServer.enqueue(
          MockResponse(
            code = 200,
            body = """{"accessToken":"new-access","refreshToken":"new-refresh"}""",
          ),
        )
        tlsServer.enqueue(MockResponse(code = 206, body = "audio"))
        val media =
          Request.Builder()
            .url(tlsServer.url("/books/api/v1/audiobooks/7/assets/2/content"))
            .header("Range", "bytes=4-")
            .build()
        http.authenticatedClient.newCall(media).execute().use { assertEquals(206, it.code) }

        tlsServer.enqueue(MockResponse(code = 204))
        bookOrbit.signOut()
        assertNull(accounts.data.first())

        val requests = List(9) { tlsServer.takeRequest() }
        assertEquals(
          listOf(
            "server-identity",
            "auth/setup-status",
            "auth/login-options",
            "app-info",
            "auth/login",
            "audiobooks/7/assets/2/content",
            "auth/refresh",
            "audiobooks/7/assets/2/content",
            "auth/logout",
          ),
          requests.map { it.url.encodedPath.removePrefix("/books/api/v1/") },
        )
        assertTrue(requests.all { it.headers["CF-Access-Client-Id"] == access.clientId })
        assertTrue(requests.all { it.headers["CF-Access-Client-Secret"] == access.clientSecret })
        assertEquals("Bearer new-access", requests[7].headers["Authorization"])
        assertEquals("bytes=4-", requests[7].headers["Range"])
      }
    }

  @Test
  fun `cloudflare discovery requires HTTPS and does not follow redirects or try other paths`() =
    runTest {
      val access = CloudflareAccess("test-client", "test-secret")
      val accounts = MemoryDataStore<Account?>(null)
      val gate = ServerConnectionGate()
      MockWebServer().use { tlsServer ->
        val http = tlsServer.startTlsClient(accounts, gate)
        val bookOrbit = bookOrbit(accounts, connectionGate = gate, http = http)
        assertEquals(
          FindServerResult.CloudflareAccessFailed,
          bookOrbit.findServer(server.url("/").toString(), access),
        )

        tlsServer.enqueue(
          MockResponse(
            code = 302,
            headers = Headers.headersOf("Location", server.url("/collect").toString()),
          ),
        )
        val root = tlsServer.url("/books/").toString()
        assertEquals(FindServerResult.CloudflareAccessFailed, bookOrbit.findServer(root, access))
        assertFailsWith<java.io.IOException> {
          http
            .clientFor(root, access)
            .newCall(Request.Builder().url(tlsServer.url("/api/v1/server-identity")).build())
            .execute()
            .close()
        }
        assertEquals(1, tlsServer.requestCount)
        assertEquals(0, server.requestCount)
      }
    }

  @Test
  fun `a page belonging to another product never receives a password`() = runTest {
    server.enqueue(
      MockResponse(
        code = 200,
        body = """{"product":"another-app","protocolVersion":1,"instanceId":"$INSTANCE_ID"}""",
      ),
    )

    assertEquals(FindServerResult.NotFound, bookOrbit().findServer(server.url("/").toString()))

    assertEquals(1, server.requestCount)
  }

  @Test
  fun `a server that still needs initial setup cannot be enrolled`() = runTest {
    server.enqueue(MockResponse(code = 200, body = identity()))
    server.enqueue(MockResponse(code = 200, body = """{"needsSetup":true}"""))

    assertEquals(FindServerResult.NotSetUp, bookOrbit().findServer(server.url("/").toString()))

    assertEquals(2, server.requestCount)
  }

  @Test
  fun `an unsupported identity protocol gets an update required result`() = runTest {
    server.enqueue(
      MockResponse(
        code = 200,
        body = """{"product":"bookorbit","protocolVersion":2,"instanceId":"$INSTANCE_ID"}""",
      ),
    )

    assertIs<FindServerResult.TooOld>(bookOrbit().findServer(server.url("/").toString()))

    assertEquals(1, server.requestCount)
  }

  @Test
  fun `SSO only servers do not receive repeated password login attempts`() = runTest {
    enqueueDiscovery(passwordLogin = false)
    val bookOrbit = bookOrbit()
    val found = assertIs<FindServerResult.Found>(bookOrbit.findServer(server.url("/").toString()))

    assertFalse(found.server.passwordLogin)
    assertEquals(LoginResult.Failed, bookOrbit.login(found.server, "listener", "password"))
    assertEquals(4, server.requestCount)
  }

  @Test
  fun `a proxy returning HTML instead of native login data is handled`() = runTest {
    enqueueDiscovery()
    server.enqueue(MockResponse(code = 200, body = "<html>Proxy sign in</html>"))
    val bookOrbit = bookOrbit()
    val found = assertIs<FindServerResult.Found>(bookOrbit.findServer(server.url("/").toString()))

    assertEquals(LoginResult.Failed, bookOrbit.login(found.server, "listener", "password"))
  }

  @Test
  fun `relogin cannot silently replace the enrolled user`() = runTest {
    val account = testAccount(server.url("/").toString())
    val accounts = MemoryDataStore<Account?>(account)

    assertFalse(bookOrbit(accounts).renewLogin(BookOrbitLogin(account.copy(userId = 8))))

    assertEquals(account, accounts.data.first())
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `signing out of BookOrbit keeps Audiobookshelf playing and removes only BookOrbit content`() =
    runTest {
      val account = testAccount(server.url("/").toString())
      val accounts = MemoryDataStore<Account?>(account)
      val owned = testBook().content
      val abs = owned.copy(id = BookId("abs://item/book"))
      val local = owned.copy(id = BookId("content://device/book"))
      val current = MemoryDataStore<BookId?>(abs.id)
      val contents =
        mockk<BookContentRepo>(relaxUnitFun = true) {
          coEvery { all() } returns listOf(owned, abs, local)
        }
      val player = mockk<PlayerController>(relaxUnitFun = true)
      val downloads = mockk<BookOrbitDownloads>(relaxUnitFun = true)
      server.enqueue(MockResponse(code = 503))

      bookOrbit(accounts, current, contents, player, downloads).signOut()

      assertNull(accounts.data.first())
      assertEquals(abs.id, current.data.first())
      verify(exactly = 0) { player.pause() }
      coVerify(exactly = 1) { downloads.removeAll() }
      coVerify(exactly = 1) { contents.put(owned.copy(isActive = false)) }
      coVerify(exactly = 0) { contents.put(match { it.id == abs.id || it.id == local.id }) }
    }

  @Test
  fun `an enrolled account remains connected while its tokens need renewal`() = runTest {
    val accounts = MemoryDataStore<Account?>(testAccount().copy(needsLogin = true))
    val bookOrbit = bookOrbit(accounts)

    assertTrue(bookOrbit.isConnected())
    assertTrue(bookOrbit.connection.first()!!.needsLogin)
  }

  @Test
  fun `an active Audiobookshelf connection blocks BookOrbit enrollment without removing downloads`() =
    runTest {
      val accounts = MemoryDataStore<Account?>(null)
      val gate = ServerConnectionGate()
      assertTrue(gate.claim("abs"))
      val downloads = mockk<BookOrbitDownloads>(relaxUnitFun = true)

      assertFalse(
        bookOrbit(accounts, downloads = downloads, connectionGate = gate)
          .connect(BookOrbitLogin(testAccount()), listOf("3")),
      )

      assertNull(accounts.data.first())
      assertEquals("abs", gate.provider.value)
      coVerify(exactly = 0) { downloads.removeAll() }
      assertEquals(0, server.requestCount)
    }

  @Test
  fun `signout retains owner scoped unsent progress and listening sessions for a later reconnect`() =
    runTest {
      val account = testAccount(server.url("/").toString())
      val key = testBook().id.value
      val request =
        SessionRequest(
          "d49a3a62-e474-48b3-b8bf-b3e6d57e430f",
          "2026-10-30T10:00:00Z",
          "2026-10-30T10:00:15Z",
          15,
        )
      val pendingSessions =
        MemoryDataStore(mapOf("$key/${request.sessionId}" to PendingSession(101, request)))
      val pending =
        PendingProgress(
          PlaybackUpdate(
            FIRST_ASSET_ID,
            20_000,
            "2026-10-30T10:00:15Z",
            request.sessionId,
            1,
            testManifest().revision,
          ),
          20_000,
          false,
        )
      val progress = MemoryDataStore(mapOf(key to SyncedProgress(pending = pending)))
      val gate = ServerConnectionGate()
      val accounts = MemoryDataStore<Account?>(account)
      val bookOrbit =
        bookOrbit(
          accounts,
          connectionGate = gate,
          pendingSessions = pendingSessions,
          progress = progress,
        )
      server.enqueue(MockResponse(code = 200))

      bookOrbit.signOut()

      assertNull(accounts.data.first())
      assertNull(gate.provider.value)
      assertEquals(pending, progress.data.first().getValue(key).pending)
      assertEquals(request, pendingSessions.data.first().values.single().request)
    }

  @Test
  fun `a saved BookOrbit account stays inactive while Audiobookshelf owns the connection gate`() =
    runTest {
      val saved = testAccount()
      val accounts = MemoryDataStore<Account?>(saved)
      val gate = ServerConnectionGate()
      assertTrue(gate.claim("abs"))
      val bookOrbit = bookOrbit(accounts, connectionGate = gate)

      assertFalse(bookOrbit.isConnected())
      assertNull(bookOrbit.connection.first())
      assertEquals(saved, accounts.data.first())
      assertEquals("abs", gate.provider.value)
    }

  private fun enqueueDiscovery(passwordLogin: Boolean = true) =
    server.enqueueDiscovery(passwordLogin)

  private fun MockWebServer.enqueueDiscovery(passwordLogin: Boolean = true) {
    enqueue(MockResponse(code = 200, body = identity()))
    enqueue(MockResponse(code = 200, body = """{"needsSetup":false}"""))
    enqueue(MockResponse(code = 200, body = """{"passwordLoginEnabled":$passwordLogin}"""))
    enqueue(MockResponse(code = 200, body = """{"version":"3.4.0"}"""))
  }

  private fun MockWebServer.startTlsClient(
    accounts: MemoryDataStore<Account?>,
    gate: ServerConnectionGate,
  ): BookOrbitHttp {
    val certificate =
      HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build()
    val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val clientCertificates =
      HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    useHttps(serverCertificates.sslSocketFactory())
    start()
    return BookOrbitHttp(
      AccountStore(accounts),
      mockk { every { versionName } returns "test" },
      gate,
      OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager),
    )
  }

  private fun identity() =
    """{"product":"bookorbit","protocolVersion":1,"instanceId":"$INSTANCE_ID"}"""

  private fun bookOrbit(
    accounts: MemoryDataStore<Account?> = MemoryDataStore(null),
    current: MemoryDataStore<BookId?> = MemoryDataStore(null),
    contents: BookContentRepo = mockk(relaxed = true),
    player: PlayerController = mockk(relaxUnitFun = true),
    downloads: BookOrbitDownloads = mockk(relaxUnitFun = true),
    connectionGate: ServerConnectionGate = ServerConnectionGate(),
    pendingSessions: MemoryDataStore<Map<String, PendingSession>> = MemoryDataStore(emptyMap()),
    progress: MemoryDataStore<Map<String, SyncedProgress>> = MemoryDataStore(emptyMap()),
    http: BookOrbitHttp =
      BookOrbitHttp(
        AccountStore(accounts),
        mockk { every { versionName } returns "test" },
        connectionGate,
      ),
  ) =
    BookOrbitImpl(
      accountStore = AccountStore(accounts),
      http = http,
      sync = mockk(relaxed = true),
      downloads = downloads,
      contentRepo = contents,
      currentBookStore = CurrentBookStore(current),
      playerController = player,
      context = mockk(),
      cachedBooksStore = CachedBooksStore(MemoryDataStore(emptyMap())),
      syncedProgressStore = SyncedProgressStore(progress),
      syncedBookmarksStore = SyncedBookmarksStore(MemoryDataStore(emptyMap())),
      pendingSessionsStore = PendingSessionsStore(pendingSessions),
      connectionGate = connectionGate,
    )
}
