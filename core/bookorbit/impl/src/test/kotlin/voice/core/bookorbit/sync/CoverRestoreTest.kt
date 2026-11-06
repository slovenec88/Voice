package voice.core.bookorbit.sync

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.bookorbit.MemoryBookContentRepo
import voice.core.bookorbit.MemoryBookmarkRepo
import voice.core.bookorbit.MemoryChapterRepo
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.CachedBooksStore
import voice.core.bookorbit.api.BookFile
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.BookPage
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testBook
import voice.core.bookorbit.testCard
import voice.core.bookorbit.testConnectionGate
import voice.core.bookorbit.testManifest

@RunWith(AndroidJUnit4::class)
class CoverRestoreTest {

  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `a restored matching cover path with missing bytes downloads its image again`() = runTest {
    MockWebServer().use { server ->
      server.start()
      val account = testAccount(server.url("/books/").toString())
      val accounts = AccountStore(MemoryDataStore<Account?>(account))
      val client =
        BookOrbitHttp(
            accounts,
            mockk { every { versionName } returns "test" },
            testConnectionGate(),
          )
          .authenticatedClient
      val api =
        mockk<BookOrbitApi> {
          coEvery { books(3, any()) } returns
            BookPage(
              listOf(
                testCard()
                  .copy(
                    files = listOf(BookFile(101, "mp3")),
                    hasCover = true,
                    coverVersion = "v1",
                    updatedAt = "2026-10-30T10:00:00Z",
                  ),
              ),
              total = 1,
            )
          coEvery { manifest(42) } returns testManifest()
        }
      val http =
        mockk<BookOrbitHttp> {
          every { authenticatedApi(account) } returns api
          every { authenticatedClient } returns client
        }
      val context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
          override fun getFilesDir() = temporary.root
        }
      val contents = MemoryBookContentRepo()
      val sync =
        LibrarySync(
          http,
          accounts,
          contents,
          MemoryChapterRepo(),
          MemoryBookmarkRepo(),
          CachedBooksStore(MemoryDataStore(emptyMap())),
          context,
        )
      val bytes =
        ByteArrayOutputStream().use { output ->
          check(
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
              .compress(Bitmap.CompressFormat.PNG, 100, output),
          )
          output.toByteArray()
        }
      fun enqueueCover() =
        server.enqueue(
          MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "image/png")
            .body(Buffer().write(bytes))
            .build(),
        )
      enqueueCover()
      sync.sync(account)
      val cover = assertNotNull(contents.get(testBook().id)!!.metadata.cover)
      assertTrue(cover.isFile)
      assertTrue(cover.delete())
      assertFalse(cover.exists())
      enqueueCover()

      sync.sync(account)

      assertEquals(cover, contents.get(testBook().id)!!.metadata.cover)
      assertTrue(cover.isFile)
      assertEquals(2, server.requestCount)
    }
  }
}
