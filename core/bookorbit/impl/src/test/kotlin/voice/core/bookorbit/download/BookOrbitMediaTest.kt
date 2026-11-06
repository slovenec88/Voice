package voice.core.bookorbit.download

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testBook
import voice.core.bookorbit.testConnectionGate
import voice.core.server.ServerConnectionGate

@RunWith(AndroidJUnit4::class)
class BookOrbitMediaTest {

  @get:Rule val temporary = TemporaryFolder()

  private val server = MockWebServer()
  private lateinit var accounts: MemoryDataStore<Account?>
  private lateinit var media: BookOrbitMedia
  private lateinit var gate: ServerConnectionGate
  private val chapter = testBook().chapters.first()

  @Before
  fun setUp() {
    server.start()
    accounts = MemoryDataStore(testAccount(server.url("/books/").toString()))
    val noBackup = temporary.newFolder("no-backup")
    val context =
      object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir() = noBackup
      }
    val store = AccountStore(accounts)
    gate = testConnectionGate()
    media =
      BookOrbitMedia(
        context,
        BookOrbitHttp(store, mockk { every { versionName } returns "test" }, gate),
        store,
        gate,
      )
  }

  @After
  fun tearDown() {
    media.cache.release()
    media.databaseProvider.close()
    server.close()
  }

  @Test
  fun `resolving an asset preserves its byte range and removes the internal owner header`() {
    server.enqueue(
      MockResponse(
        code = 206,
        headers = Headers.headersOf("Content-Range", "bytes 2-5/8", "Content-Type", "audio/mpeg"),
        body = "cdef",
      ),
    )
    val spec = DataSpec.Builder().setUri(chapter.id.value).setPosition(2).setLength(4).build()

    assertEquals("cdef", read(media.playbackDataSourceFactory, spec))

    val request = server.takeRequest(1, TimeUnit.SECONDS)!!
    assertEquals(
      "/books/api/v1/audiobooks/42/assets/aud_aaaaaaaa-1111-4111-8111-111111111111/content",
      request.url.encodedPath,
    )
    assertEquals("bytes=2-5", request.headers["Range"])
    assertEquals("Bearer access-one", request.headers["Authorization"])
    assertNull(request.headers["X-Voice-Owner"])
  }

  @Test
  fun `ordinary streaming never writes a persistent download`() {
    server.enqueue(MockResponse(code = 200, body = "abcdefgh"))

    assertEquals("abcdefgh", read(media.playbackDataSourceFactory))

    assertEquals(emptySet(), media.cache.keys)
  }

  @Test
  fun `a chosen download plays from immutable cache while the same owner needs login`() = runTest {
    server.enqueue(MockResponse(code = 200, body = "abcdefgh"))
    assertEquals("abcdefgh", read(media.downloadDataSourceFactory))
    assertTrue(chapter.id.value in media.cache.keys)
    accounts.updateData { it!!.copy(needsLogin = true, accessToken = "expired") }

    assertEquals("abcdefgh", read(media.playbackDataSourceFactory))

    assertEquals(1, server.requestCount)
  }

  @Test
  fun `a cached asset cannot be opened by a replacement account`() = runTest {
    server.enqueue(MockResponse(code = 200, body = "abcdefgh"))
    assertEquals("abcdefgh", read(media.downloadDataSourceFactory))
    accounts.updateData { it!!.copy(userId = 8) }

    assertFailsWith<IOException> { read(media.playbackDataSourceFactory) }

    assertEquals(1, server.requestCount)
  }

  @Test
  fun `a cached BookOrbit asset is inaccessible while Audiobookshelf is selected`() = runTest {
    server.enqueue(MockResponse(code = 200, body = "abcdefgh"))
    assertEquals("abcdefgh", read(media.downloadDataSourceFactory))
    gate.release("bo")
    assertTrue(gate.claim("abs"))

    assertFailsWith<IOException> { read(media.playbackDataSourceFactory) }

    assertEquals(1, server.requestCount)
  }

  private fun read(
    factory: DataSource.Factory,
    spec: DataSpec = DataSpec.Builder().setUri(chapter.id.value).build(),
  ): String {
    val source = factory.createDataSource()
    return try {
      source.open(spec)
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(16)
      while (true) {
        val count = source.read(buffer, 0, buffer.size)
        if (count < 0) break
        output.write(buffer, 0, count)
      }
      output.toString(Charsets.UTF_8)
    } finally {
      source.close()
    }
  }
}
