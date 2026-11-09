package voice.core.bookorbit

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.download.BookOrbitDownloads
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.data.BookId
import voice.core.data.repo.BookContentRepo
import voice.core.data.store.CurrentBookStore
import voice.core.playback.PlayerController
import voice.core.server.ServerConnectionGate

class BookOrbitLoginTest {

  @get:Rule val temporary = TemporaryFolder()

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
  fun `login network failure exposes only its type and never the exception message`() = runTest {
    enqueueDiscovery()
    val base =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          if (chain.request().url.encodedPath.endsWith("/auth/login")) {
            throw SocketTimeoutException("private-server-address listener password test-secret")
          }
          chain.proceed(chain.request())
        }
        .build()
    val accounts = MemoryDataStore<Account?>(null)
    val gate = ServerConnectionGate()
    val bookOrbit =
      bookOrbit(
        accounts,
        connectionGate = gate,
        http = BookOrbitHttp(AccountStore(accounts), base, gate),
      )
    val found = assertIs<FindServerResult.Found>(bookOrbit.findServer(server.url("/").toString()))

    val result =
      assertIs<LoginResult.Unreachable>(bookOrbit.login(found.server, "listener", "password"))

    assertEquals("SocketTimeoutException", result.reason)
    assertNull(accounts.data.first())
    assertEquals(4, server.requestCount)
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

  @Test
  fun `connected preview covers use refreshed credentials`() = runTest {
    val account = testAccount(server.url("/books/").toString())
    val accounts = MemoryDataStore<Account?>(account)
    server.enqueue(MockResponse(code = 401))
    server.enqueue(
      MockResponse(
        code = 200,
        body = """{"accessToken":"access-two","refreshToken":"refresh-two"}""",
      ),
    )
    enqueuePreview()

    val preview = assertNotNull(bookOrbit(accounts, context = previewContext()).libraries())

    assertEquals(1, preview.covers.size)
    assertEquals("access-two", accounts.data.first()!!.accessToken)
    val requests = List(5) { server.takeRequest() }
    assertEquals("Bearer access-two", requests.last().headers["Authorization"])
    assertEquals("/books/api/v1/books/42/cover", requests.last().url.encodedPath)
  }

  @Test
  fun `a pending login can preview covers before enrollment`() = runTest {
    val account = testAccount(server.url("/books/").toString())
    val accounts = MemoryDataStore<Account?>(null)
    enqueuePreview()

    val preview =
      assertIs<LibrariesPreviewResult.Success>(
          bookOrbit(accounts, context = previewContext()).preview(BookOrbitLogin(account)),
        )
        .preview

    assertEquals(1, preview.covers.size)
    assertNull(accounts.data.first())
    val requests = List(3) { server.takeRequest() }
    assertTrue(requests.all { it.headers["Authorization"] == "Bearer access-one" })
  }

  @Test
  fun `a connected preview cannot fetch covers with another owners credentials`() = runTest {
    val account = testAccount(server.url("/books/").toString())
    val accounts = MemoryDataStore<Account?>(account)
    val replacement =
      account.copy(userId = 8, accessToken = "other-access", refreshToken = "other-refresh")
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          if (request.url.encodedPath.endsWith("/libraries")) {
            MockResponse(code = 200, body = previewLibraries())
          } else {
            runBlocking { accounts.updateData { replacement } }
            MockResponse(code = 200, body = previewPage())
          }
      }

    val preview = assertNotNull(bookOrbit(accounts, context = previewContext()).libraries())

    assertEquals(0, preview.covers.size)
    assertEquals(2, server.requestCount)
    assertEquals(replacement, accounts.data.first())
  }

  @Test
  fun `preview distinguishes rejected library and catalog requests without exposing response bodies`() =
    runTest {
      val account = testAccount(server.url("/").toString())
      val bookOrbit = bookOrbit(context = previewContext())
      server.enqueue(MockResponse(code = 403, body = "private server detail"))
      assertEquals(
        LibrariesPreviewResult.Failed("BO-LIBRARIES-HTTP403"),
        bookOrbit.preview(BookOrbitLogin(account)),
      )
      server.enqueue(MockResponse(code = 200, body = previewLibraries()))
      server.enqueue(MockResponse(code = 400, body = "private book detail"))
      assertEquals(
        LibrariesPreviewResult.Failed("BO-BOOKS-HTTP400"),
        bookOrbit.preview(BookOrbitLogin(account)),
      )
    }

  @Test
  fun `preview distinguishes invalid library and catalog responses`() = runTest {
    val account = testAccount(server.url("/").toString())
    val bookOrbit = bookOrbit(context = previewContext())
    server.enqueue(MockResponse(code = 200, body = """[{"id":"private invalid id"}]"""))
    assertEquals(
      LibrariesPreviewResult.Failed("BO-LIBRARIES-JSON"),
      bookOrbit.preview(BookOrbitLogin(account)),
    )
    server.enqueue(MockResponse(code = 200, body = previewLibraries()))
    server.enqueue(MockResponse(code = 200, body = """{"items":[{"id":"private invalid id"}]}"""))
    assertEquals(
      LibrariesPreviewResult.Failed("BO-BOOKS-JSON"),
      bookOrbit.preview(BookOrbitLogin(account)),
    )
  }

  @Test
  fun `fresh login previews libraries through Cloudflare before account enrollment`() = runTest {
    MockWebServer().use { secure ->
      val accounts = MemoryDataStore<Account?>(null)
      val gate = ServerConnectionGate()
      val http = secure.startTlsClient(accounts, gate)
      secure.enqueueDiscovery()
      secure.enqueue(MockResponse(code = 200, body = fixture("native-login")))
      secure.enqueue(MockResponse(code = 200, body = previewLibraries()))
      secure.enqueue(MockResponse(code = 200, body = previewPage()))
      secure.enqueue(
        MockResponse(
          code = 200,
          headers = Headers.headersOf("Content-Type", "image/png"),
          body = "image bytes",
        ),
      )
      val access = CloudflareAccess("test-id", "test-secret")
      val bookOrbit =
        bookOrbit(accounts, connectionGate = gate, context = previewContext(), http = http)
      val found =
        assertIs<FindServerResult.Found>(bookOrbit.findServer(secure.url("/").toString(), access))
      val login =
        assertIs<LoginResult.Success>(bookOrbit.login(found.server, "listener", "password"))

      val result = assertIs<LibrariesPreviewResult.Success>(bookOrbit.preview(login.login))

      assertEquals(1, result.preview.libraries.single().bookCount)
      assertEquals(1, result.preview.covers.size)
      assertNull(accounts.data.first())
      val requests = List(8) { secure.takeRequest() }
      assertTrue(
        requests.all {
          it.headers["CF-Access-Client-Id"] == access.clientId &&
            it.headers["CF-Access-Client-Secret"] == access.clientSecret
        },
      )
      assertTrue(requests.takeLast(3).all { it.headers["Authorization"] == "Bearer access-one" })
    }
  }

  private fun previewContext() = mockk<Context> { every { cacheDir } returns temporary.root }

  private fun previewLibraries() = fixture("libraries")

  private fun previewPage() =
    """{"items":[{"id":42,"files":[{"id":101,"format":"mp3","role":"content"}],"hasCover":true}],"total":1,"page":0,"size":8}"""

  private fun enqueuePreview() {
    server.enqueue(MockResponse(code = 200, body = previewLibraries()))
    server.enqueue(MockResponse(code = 200, body = previewPage()))
    server.enqueue(
      MockResponse(
        code = 200,
        headers = Headers.headersOf("Content-Type", "image/png"),
        body = "image bytes",
      ),
    )
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
      OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build(),
      gate,
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
    context: Context = mockk(),
    http: BookOrbitHttp =
      BookOrbitHttp(
        AccountStore(accounts),
        OkHttpClient(),
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
      context = context,
      connectionGate = connectionGate,
    )
}
