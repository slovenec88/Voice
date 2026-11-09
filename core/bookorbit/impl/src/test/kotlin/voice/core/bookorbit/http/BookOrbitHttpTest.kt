package voice.core.bookorbit.http

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import retrofit2.HttpException
import voice.core.bookorbit.CloudflareAccess
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.api.LoginRequest
import voice.core.bookorbit.fixture
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testConnectionGate
import voice.core.server.ServerConnectionGate

class BookOrbitHttpTest {

  private val server = MockWebServer()
  private lateinit var account: Account
  private lateinit var accounts: MemoryDataStore<Account?>

  @Before
  fun setUp() {
    server.start()
    account = testAccount(server.url("/books/").toString())
    accounts = MemoryDataStore(account)
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun `server clients retain the shared pool and user agent without following redirects`() =
    runTest {
      val shared =
        OkHttpClient.Builder()
          .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Voice/test").build())
          }
          .build()
      val http = BookOrbitHttp(AccountStore(accounts), shared, testConnectionGate())
      server.enqueue(MockResponse(code = 200, body = user()))

      assertEquals(7L, http.authenticatedApi(account).me().id)

      assertSame(shared.connectionPool, http.client.connectionPool)
      assertSame(shared.dispatcher, http.authenticatedClient.dispatcher)
      assertEquals("Voice/test", server.takeRequest(1, TimeUnit.SECONDS)!!.headers["User-Agent"])
      assertFalse(http.client.followRedirects)
      assertFalse(http.authenticatedClient.followSslRedirects)
    }

  @Test
  fun `native login uses the versioned API under a reverse proxy and JSON credentials`() = runTest {
    server.enqueue(MockResponse(code = 200, body = fixture("native-login")))

    val response = http().api(account.serverUrl).login(LoginRequest("listener", "password"))

    assertEquals(7L, response.user.id)
    val request = server.takeRequest(1, TimeUnit.SECONDS)!!
    assertEquals("POST", request.method)
    assertEquals("/books/api/v1/auth/login", request.url.encodedPath)
    assertNull(request.headers["Authorization"])
    assertNull(request.headers["x-return-tokens"])
    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
    assertEquals("native", body.getValue("clientKind").jsonPrimitive.content)
    assertEquals("Voice", body.getValue("deviceLabel").jsonPrimitive.content)
    assertEquals("listener", body.getValue("username").jsonPrimitive.content)
    assertEquals("password", body.getValue("password").jsonPrimitive.content)
  }

  @Test
  fun `a broken fresh login connection retries once over HTTP1 with Cloudflare credentials`() =
    runTest {
      MockWebServer().use { tls ->
        val trustedClient = tlsClient(tls)
        val attempts = AtomicInteger()
        val protocols = ConcurrentLinkedQueue<Protocol>()
        val base =
          trustedClient
            .newBuilder()
            .addInterceptor { chain ->
              if (attempts.incrementAndGet() == 1) throw EOFException("connection closed")
              chain.proceed(chain.request())
            }
            .addNetworkInterceptor { chain ->
              protocols.add(chain.connection()!!.protocol())
              chain.proceed(chain.request())
            }
            .build()
        val http = BookOrbitHttp(AccountStore(accounts), base, ServerConnectionGate())
        val access = CloudflareAccess("test-client", "test-secret")
        tls.enqueue(MockResponse(code = 200, body = fixture("native-login")))

        val response =
          http.login(tls.url("/books/").toString(), access, LoginRequest("listener", "password"))

        assertEquals(7L, response.user.id)
        assertEquals(2, attempts.get())
        assertEquals(listOf(Protocol.HTTP_1_1), protocols.toList())
        assertTrue(Protocol.HTTP_2 in http.client.protocols)
        val request = tls.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/books/api/v1/auth/login", request.url.encodedPath)
        assertEquals(access.clientId, request.headers["CF-Access-Client-Id"])
        assertEquals(access.clientSecret, request.headers["CF-Access-Client-Secret"])
        assertNull(request.headers["Authorization"])
      }
    }

  @Test
  fun `a reset HTTP2 login stream recovers on HTTP1 without losing Cloudflare headers`() = runTest {
    MockWebServer().use { tls ->
      val protocols = ConcurrentLinkedQueue<Protocol>()
      val base =
        tlsClient(tls)
          .newBuilder()
          .retryOnConnectionFailure(false)
          .addNetworkInterceptor { chain ->
            protocols.add(chain.connection()!!.protocol())
            chain.proceed(chain.request())
          }
          .build()
      val http = BookOrbitHttp(AccountStore(accounts), base, ServerConnectionGate())
      val access = CloudflareAccess("test-client", "test-secret")
      tls.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseStream(1)).build())
      tls.enqueue(MockResponse(code = 200, body = fixture("native-login")))

      val response =
        http.login(tls.url("/books/").toString(), access, LoginRequest("listener", "password"))

      assertEquals(7L, response.user.id)
      assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), protocols.toList())
      val requests = List(2) { tls.takeRequest(1, TimeUnit.SECONDS)!! }
      assertTrue(requests.all { it.headers["CF-Access-Client-Id"] == access.clientId })
      assertTrue(requests.all { it.headers["CF-Access-Client-Secret"] == access.clientSecret })
      assertTrue(requests.all { it.url.encodedPath == "/books/api/v1/auth/login" })
    }
  }

  @Test
  fun `login recovery cannot follow a redirect with passwords or service credentials`() = runTest {
    MockWebServer().use { tls ->
      MockWebServer().use { other ->
        other.start()
        val attempts = AtomicInteger()
        val base =
          tlsClient(tls)
            .newBuilder()
            .addInterceptor { chain ->
              if (attempts.incrementAndGet() == 1) throw EOFException("closed")
              chain.proceed(chain.request())
            }
            .build()
        val http = BookOrbitHttp(AccountStore(accounts), base, ServerConnectionGate())
        tls.enqueue(
          MockResponse(
            code = 302,
            headers = Headers.headersOf("Location", other.url("/collect").toString()),
          ),
        )

        assertEquals(
          302,
          assertFailsWith<HttpException> {
              http.login(
                tls.url("/books/").toString(),
                CloudflareAccess("test-client", "test-secret"),
                LoginRequest("listener", "password"),
              )
            }
            .code(),
        )

        assertEquals(0, other.requestCount)
        assertEquals(1, tls.requestCount)
      }
    }
  }

  @Test
  fun `fresh login does not retry certificate DNS or timeout failures`() = runTest {
    for (failure in
      listOf(
        SSLHandshakeException("certificate"),
        UnknownHostException("lookup"),
        SocketTimeoutException("timeout"),
      )) {
      val attempts = AtomicInteger()
      val base =
        OkHttpClient.Builder()
          .addInterceptor {
            attempts.incrementAndGet()
            throw failure
          }
          .build()
      val http = BookOrbitHttp(AccountStore(accounts), base, ServerConnectionGate())

      val caught =
        assertFailsWith<IOException> {
          http.login(account.serverUrl, null, LoginRequest("listener", "password"))
        }

      assertEquals(failure::class, caught::class)
      assertEquals(failure.message, caught.message)
      assertEquals(1, attempts.get())
    }
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `fresh login recovery is bounded and never retries rejected credentials`() = runTest {
    val attempts = AtomicInteger()
    val base =
      OkHttpClient.Builder()
        .addInterceptor {
          attempts.incrementAndGet()
          throw EOFException("connection closed")
        }
        .build()
    val broken = BookOrbitHttp(AccountStore(accounts), base, ServerConnectionGate())
    assertFailsWith<EOFException> {
      broken.login(account.serverUrl, null, LoginRequest("listener", "password"))
    }
    assertEquals(2, attempts.get())

    server.enqueue(MockResponse(code = 401))
    assertEquals(
      401,
      assertFailsWith<HttpException> {
          http().login(account.serverUrl, null, LoginRequest("listener", "wrong"))
        }
        .code(),
    )
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `bearer credentials are sent only inside the enrolled origin and API path`() = runTest {
    val client = http().authenticatedClient
    listOf(
        "/books/api/v1/auth/me" to "Bearer access-one",
        "/books-evil/api/v1/auth/me" to null,
        "/books/api/v1-other/auth/me" to null,
        "/api/v1/auth/me" to null,
        "/books/cover" to null,
      )
      .forEach { (path, expected) ->
        server.enqueue(MockResponse(code = 200))
        client
          .newCall(
            Request.Builder().url(server.url(path)).header("Authorization", "Bearer stale").build(),
          )
          .execute()
          .close()

        assertEquals(expected, server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
      }
    MockWebServer().use { other ->
      other.start()
      other.enqueue(MockResponse(code = 200))
      client
        .newCall(Request.Builder().url(other.url("/books/api/v1/auth/me")).build())
        .execute()
        .close()
      assertNull(other.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
    }
  }

  @Test
  fun `authenticated redirects are not followed to another server`() = runTest {
    MockWebServer().use { other ->
      other.start()
      server.enqueue(
        MockResponse(
          code = 302,
          headers = Headers.headersOf("Location", other.url("/collect").toString()),
        ),
      )

      http()
        .authenticatedClient
        .newCall(Request.Builder().url(server.url("/books/api/v1/auth/me")).build())
        .execute()
        .use {
          assertEquals(302, it.code)
        }

      assertEquals(0, other.requestCount)
      assertEquals(1, server.requestCount)
    }
  }

  @Test
  fun `rotating native tokens retries the failed request once`() = runTest {
    server.enqueue(MockResponse(code = 401))
    server.enqueue(
      MockResponse(
        code = 200,
        body = """{"accessToken":"access-two","refreshToken":"refresh-two"}""",
      ),
    )
    server.enqueue(MockResponse(code = 200, body = user()))

    assertEquals(7L, http().authenticatedApi(account).me().id)

    val requests = List(3) { server.takeRequest(1, TimeUnit.SECONDS)!! }
    assertEquals("/books/api/v1/auth/refresh", requests[1].url.encodedPath)
    assertNull(requests[1].headers["Authorization"])
    assertEquals(
      "refresh-one",
      Json.parseToJsonElement(requests[1].body!!.utf8())
        .jsonObject
        .getValue("refreshToken")
        .jsonPrimitive
        .content,
    )
    assertEquals("Bearer access-two", requests[2].headers["Authorization"])
    assertEquals("access-two", accounts.data.first()!!.accessToken)
    assertEquals("refresh-two", accounts.data.first()!!.refreshToken)
  }

  @Test
  fun `malformed refresh responses retain the account and return the original unauthorized response`() =
    runTest {
      for (body in listOf("{}", "<html>Proxy error</html>")) {
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse(code = 200, body = body))

        http()
          .authenticatedClient
          .newCall(Request.Builder().url(server.url("/books/api/v1/auth/me")).build())
          .execute()
          .use { assertEquals(401, it.code) }

        assertEquals(account, accounts.data.first())
      }
      assertEquals(4, server.requestCount)
    }

  @Test
  fun `malformed refresh JSON does not escape a suspended request worker`() = runTest {
    val uncaught = ConcurrentLinkedQueue<Throwable>()
    val executor = Executors.newCachedThreadPool { task ->
      Thread(task).apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
          uncaught.add(error)
        }
      }
    }
    val client =
      BookOrbitHttp(
        AccountStore(accounts),
        OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher(executor)).build(),
        testConnectionGate(),
      )
    server.enqueue(MockResponse(code = 401))
    server.enqueue(MockResponse(code = 200, body = "{}"))
    try {
      assertEquals(
        401,
        assertFailsWith<HttpException> { client.authenticatedApi(account).me() }.code(),
      )
      assertEquals(account, accounts.data.first())
    } finally {
      executor.shutdown()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
    assertTrue(uncaught.isEmpty())
  }

  @Test
  fun `concurrent expired requests share one refresh operation`() = runTest {
    val initialRequests = CountDownLatch(2)
    val refreshes = AtomicInteger()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          return when {
            request.url.encodedPath.endsWith("/auth/refresh") -> {
              refreshes.incrementAndGet()
              MockResponse(
                code = 200,
                body = """{"accessToken":"access-two","refreshToken":"refresh-two"}""",
              )
            }
            request.headers["Authorization"] == "Bearer access-one" -> {
              initialRequests.countDown()
              check(initialRequests.await(5, TimeUnit.SECONDS))
              MockResponse(code = 401)
            }
            else -> MockResponse(code = 200, body = user())
          }
        }
      }
    val api = http().authenticatedApi(account)

    val users = List(2) { async(Dispatchers.IO) { api.me() } }.awaitAll()

    assertEquals(listOf(7L, 7L), users.map { it.id })
    assertEquals(1, refreshes.get())
    assertEquals("refresh-two", accounts.data.first()!!.refreshToken)
  }

  @Test
  fun `a rejected refresh requests login while retaining the enrolled account`() = runTest {
    server.enqueue(MockResponse(code = 401))
    server.enqueue(MockResponse(code = 401))

    assertFailsWith<HttpException> { http().authenticatedApi(account).me() }

    val stored = accounts.data.first()!!
    assertEquals(account.userId, stored.userId)
    assertEquals(account.refreshToken, stored.refreshToken)
    assertTrue(stored.needsLogin)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun `a temporary refresh failure does not sign the account out`() = runTest {
    server.enqueue(MockResponse(code = 401))
    server.enqueue(MockResponse(code = 503))

    assertFailsWith<HttpException> { http().authenticatedApi(account).me() }

    assertEquals(account, accounts.data.first())
    assertFalse(accounts.data.first()!!.needsLogin)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun `a client captured for an old account never sends the new owners token`() = runTest {
    val api = http().authenticatedApi(account)
    val replacement =
      account.copy(
        userId = 8,
        username = "other",
        accessToken = "other-access",
        refreshToken = "other-refresh",
      )
    accounts.updateData { replacement }
    server.enqueue(MockResponse(code = 200, body = user(8)))

    assertFailsWith<IOException> { api.me() }

    assertEquals(0, server.requestCount)
    assertEquals(replacement, accounts.data.first())
  }

  @Test
  fun `a refresh response cannot overwrite an account replaced while it was in flight`() = runTest {
    val replacement =
      account.copy(
        userId = 8,
        username = "other",
        accessToken = "other-access",
        refreshToken = "other-refresh",
      )
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          return if (request.url.encodedPath.endsWith("/auth/refresh")) {
            runBlocking { accounts.updateData { replacement } }
            MockResponse(
              code = 200,
              body =
                """{"accessToken":"old-owner-new-access","refreshToken":"old-owner-new-refresh"}""",
            )
          } else {
            MockResponse(code = 401)
          }
        }
      }

    assertFailsWith<HttpException> { http().authenticatedApi(account).me() }

    assertEquals(replacement, accounts.data.first())
    assertEquals(2, server.requestCount)
  }

  @Test
  fun `an old media request is never retried with a replacement users credentials`() = runTest {
    val replacement =
      account.copy(
        userId = 8,
        username = "other",
        accessToken = "other-access",
        refreshToken = "other-refresh",
      )
    val calls = AtomicInteger()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          return if (calls.incrementAndGet() == 1) {
            runBlocking { accounts.updateData { replacement } }
            MockResponse(code = 401)
          } else {
            MockResponse(code = 200, body = "another users audio")
          }
        }
      }
    val request =
      Request.Builder()
        .url(server.url("/books/api/v1/audiobooks/42/assets/asset/content"))
        .header("X-Voice-Owner", "${account.instanceId}/${account.userId}")
        .build()

    http().authenticatedClient.newCall(request).execute().use { response ->
      assertEquals(401, response.code)
    }

    assertEquals(1, server.requestCount)
    assertEquals(replacement, accounts.data.first())
    assertEquals(
      "Bearer access-one",
      server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"],
    )
  }

  @Test
  fun `retained BookOrbit credentials cannot make requests while Audiobookshelf is selected`() =
    runTest {
      val gate = ServerConnectionGate()
      assertTrue(gate.claim("abs"))
      val client = BookOrbitHttp(AccountStore(accounts), OkHttpClient(), gate)

      assertFailsWith<IOException> { client.authenticatedApi(account).me() }

      assertEquals(0, server.requestCount)
      assertEquals(account, accounts.data.first())
    }

  private fun tlsClient(server: MockWebServer): OkHttpClient {
    val certificate =
      HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build()
    val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val trusted =
      HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    server.useHttps(serverCertificates.sslSocketFactory())
    server.start()
    return OkHttpClient.Builder()
      .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
      .build()
  }

  private fun http() =
    BookOrbitHttp(
      AccountStore(accounts),
      OkHttpClient(),
      testConnectionGate(),
    )

  private fun user(id: Long = 7): String = """{"id":$id,"username":"listener"}"""
}
