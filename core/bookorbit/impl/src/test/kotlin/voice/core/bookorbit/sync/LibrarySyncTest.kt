package voice.core.bookorbit.sync

import android.content.Context
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response
import voice.core.bookorbit.MemoryBookContentRepo
import voice.core.bookorbit.MemoryBookmarkRepo
import voice.core.bookorbit.MemoryChapterRepo
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.OTHER_INSTANCE_ID
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.CachedBook
import voice.core.bookorbit.account.CachedBooksStore
import voice.core.bookorbit.api.BookCard
import voice.core.bookorbit.api.BookFile
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.BookPage
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testBook
import voice.core.bookorbit.testCard
import voice.core.bookorbit.testManifest
import voice.core.bookorbit.updatingBookRepository
import voice.core.data.BookId

class LibrarySyncTest {

  @get:Rule val temporary = TemporaryFolder()

  private val account = testAccount()
  private val accounts = MemoryDataStore<Account?>(account)
  private val api = mockk<BookOrbitApi>()
  private val http =
    mockk<BookOrbitHttp> {
      every { authenticatedApi(account) } returns api
    }
  private val cached = MemoryDataStore<Map<String, CachedBook>>(emptyMap())
  private val chapters = MemoryChapterRepo(testBook().chapters)
  private val bookmarks = MemoryBookmarkRepo()
  private val context = mockk<Context>()

  @Test
  fun `an audio format beside an ebook imports through the dedicated manifest`() = runTest {
    val card = audioCard()
    coEvery { api.books(3, any()) } returns BookPage(listOf(card), total = 1)
    coEvery { api.manifest(42) } returns testManifest()
    val contents = MemoryBookContentRepo()

    sync(contents).sync(account)

    val content = assertNotNull(contents.get(testBook().id))
    assertEquals(2, content.chapters.size)
    assertEquals("A Story in Two Files", content.metadata.name)
    assertTrue(content.isActive)
    assertNotNull(cached.data.first()[content.id.value])
    coVerify(exactly = 1) {
      val _ = api.manifest(42)
    }
  }

  @Test
  fun `a failed later page preserves the complete previous library`() = runTest {
    val firstPage = List(200) { index -> audioCard().copy(id = index.toLong() + 1) }
    coEvery { api.books(3, any()) } returns
      BookPage(firstPage, total = 201) andThenThrows
      httpFailure(503)
    val existing = testBook().content
    val contents = MemoryBookContentRepo(existing)

    assertFailsWith<HttpException> { sync(contents).sync(account) }

    assertEquals(listOf(existing), contents.all())
    assertEquals(emptyMap(), cached.data.first())
    coVerify(exactly = 0) {
      val _ = api.manifest(any())
    }
  }

  @Test
  fun `a repeated library page is rejected before any removal`() = runTest {
    val firstPage = List(200) { index -> audioCard().copy(id = index.toLong() + 1) }
    coEvery { api.books(3, any()) } returns BookPage(firstPage, total = 201)
    val existing = testBook().content
    val contents = MemoryBookContentRepo(existing)

    assertFailsWith<IOException> { sync(contents).sync(account) }

    assertEquals(listOf(existing), contents.all())
  }

  @Test
  fun `a nonempty short page with a larger total is not an authoritative complete library`() =
    runTest {
      val existing = testBook().content
      val contents = MemoryBookContentRepo(existing)
      coEvery { api.books(3, any()) } returns
        BookPage(listOf(audioCard().copy(id = 43)), total = 201)

      assertFailsWith<IOException> { sync(contents).sync(account) }

      assertEquals(listOf(existing), contents.all())
      coVerify(exactly = 0) {
        val _ = api.manifest(any())
      }
    }

  @Test
  fun `an authoritative absence hides only this accounts books`() = runTest {
    coEvery { api.books(3, any()) } returns BookPage()
    val owned = testBook().content
    val otherInstance = testBook(account = account.copy(instanceId = OTHER_INSTANCE_ID)).content
    val otherUser = testBook(account = account.copy(userId = 8)).content
    val abs = owned.copy(id = BookId("abs://item/existing"))
    val local = owned.copy(id = BookId("content://device/book"))
    val contents = MemoryBookContentRepo(owned, otherInstance, otherUser, abs, local)

    sync(contents).sync(account)

    assertFalse(contents.get(owned.id)!!.isActive)
    listOf(otherInstance, otherUser, abs, local).forEach { assertEquals(it, contents.get(it.id)) }
  }

  @Test
  fun `temporarily incomplete audio metadata preserves an existing playable book`() = runTest {
    coEvery { api.books(3, any()) } returns BookPage(listOf(audioCard()), total = 1)
    coEvery { api.manifest(42) } returns
      testManifest().let { manifest ->
        manifest.copy(assets = manifest.assets.map { it.copy(durationMs = null) })
      }
    val existing = testBook(75_000).content
    val contents = MemoryBookContentRepo(existing)

    sync(contents).sync(account)

    assertEquals(existing, contents.get(existing.id))
    assertEquals(emptyMap(), cached.data.first())
  }

  @Test
  fun `a failed manifest request does not turn a server book into a deletion`() = runTest {
    coEvery { api.books(3, any()) } returns BookPage(listOf(audioCard()), total = 1)
    coEvery { api.manifest(42) } throws httpFailure(503)
    val existing = testBook(75_000).content
    val contents = MemoryBookContentRepo(existing)

    sync(contents).sync(account)

    assertEquals(existing, contents.get(existing.id))
  }

  @Test
  fun `ebook only entries and audio attachments never import as audiobooks`() = runTest {
    coEvery { api.books(3, any()) } returns
      BookPage(
        listOf(
          testCard().copy(files = listOf(BookFile(1, "epub"))),
          testCard().copy(id = 43, files = listOf(BookFile(2, "mp3", role = "attachment"))),
        ),
        total = 2,
      )
    val contents = MemoryBookContentRepo()

    sync(contents).sync(account)

    assertEquals(emptyList(), contents.all())
    coVerify(exactly = 0) {
      val _ = api.manifest(any())
    }
  }

  @Test
  fun `a replacement cover failure keeps the previous cover file and metadata`() = runTest {
    val previousCover =
      temporary.newFile("old-cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val existing =
      testBook().content.let { it.copy(metadata = it.metadata.copy(cover = previousCover)) }
    coEvery { api.books(3, any()) } returns
      BookPage(listOf(audioCard().copy(hasCover = true, coverVersion = "new-cover")), total = 1)
    coEvery { api.manifest(42) } returns testManifest()
    every { context.filesDir } returns temporary.root
    every { http.authenticatedClient } returns
      mockk {
        every { newCall(any()) } returns
          mockk {
            every { execute() } throws IOException("offline")
          }
      }
    val contents = MemoryBookContentRepo(existing)

    sync(contents).sync(account)

    assertEquals(previousCover, contents.get(existing.id)!!.metadata.cover)
    assertTrue(previousCover.exists())
    assertEquals(emptyList(), temporary.root.resolve("bookCovers").listFiles()!!.toList())
  }

  @Test
  fun `an account replaced during a library response cannot import or hide books`() = runTest {
    val existing = testBook().content
    val contents = MemoryBookContentRepo(existing)
    coEvery { api.books(3, any()) } coAnswers
      {
        accounts.updateData { account.copy(userId = 8) }
        BookPage(listOf(audioCard()), total = 1)
      }

    sync(contents).sync(account)

    assertEquals(listOf(existing), contents.all())
    coVerify(exactly = 0) {
      val _ = api.manifest(any())
    }
  }

  @Test
  fun `an unchanged library refresh avoids book and chapter writes`() = runTest {
    val contents = spyk(MemoryBookContentRepo())
    val trackedChapters = spyk(chapters)
    coEvery { api.books(3, any()) } returns BookPage(listOf(audioCard()), total = 1)
    coEvery { api.manifest(42) } returns testManifest()
    val library =
      LibrarySync(
        http,
        AccountStore(accounts),
        contents,
        updatingBookRepository(contents),
        trackedChapters,
        bookmarks,
        CachedBooksStore(cached),
        context,
      )
    library.sync(account)
    clearMocks(contents, trackedChapters, answers = false)

    library.sync(account)

    coVerify(exactly = 0) { contents.put(any()) }
    coVerify(exactly = 0) { trackedChapters.put(any()) }
  }

  @Test
  fun `an incomplete replacement manifest is a retryable refresh failure`() = runTest {
    val existing = testBook().content
    cached.updateData { mapOf(existing.id.value to CachedBook(testManifest(), audioCard())) }
    val malformed =
      testManifest().let {
        it.copy(assets = it.assets.map { asset -> asset.copy(durationMs = null) })
      }
    coEvery { api.manifest(42) } returns malformed
    val contents = MemoryBookContentRepo(existing)

    assertFailsWith<IOException> { sync(contents).refreshBook(account, 42) }

    assertEquals(existing, contents.get(existing.id))
    assertEquals(testManifest(), cached.data.first().getValue(existing.id.value).manifest)
  }

  @Test
  fun `a metadata refresh preserves playback written after its snapshot`() = runTest {
    val original = testBook()
    val contents = MemoryBookContentRepo(original.content)
    val latest =
      original.content.copy(
        positionInChapter = 12_000,
        playbackSpeed = 1.75F,
        gain = 2F,
        skipSilence = true,
        lastPlayedAt = java.time.Instant.parse("2026-11-08T00:00:00Z"),
      )
    val trackedChapters = spyk(chapters)
    coEvery { trackedChapters.get(original.chapters.first().id) } coAnswers
      {
        contents.put(latest)
        original.chapters.first()
      }
    coEvery { api.books(3, any()) } returns BookPage(listOf(audioCard()), total = 1)
    coEvery { api.manifest(42) } returns
      testManifest().let { it.copy(book = it.book.copy(title = "Updated title")) }

    LibrarySync(
        http,
        AccountStore(accounts),
        contents,
        updatingBookRepository(contents),
        trackedChapters,
        bookmarks,
        CachedBooksStore(cached),
        context,
      )
      .sync(account)

    val updated = assertNotNull(contents.get(original.id))
    assertEquals("Updated title", updated.metadata.name)
    assertEquals(latest.copy(metadata = updated.metadata), updated)
  }

  @Test
  fun `server cover removal clears the reference before deleting its file`() = runTest {
    val cover = temporary.newFile("removed-cover.jpg")
    val original = testBook().content.let { it.copy(metadata = it.metadata.copy(cover = cover)) }
    val contents = MemoryBookContentRepo(original)
    coEvery { api.books(3, any()) } returns
      BookPage(listOf(audioCard().copy(hasCover = false)), total = 1)
    coEvery { api.manifest(42) } returns testManifest()

    sync(contents).sync(account)

    assertEquals(null, contents.get(original.id)!!.metadata.cover)
    assertFalse(cover.exists())
  }

  @Test
  fun `a replacement manifest remaps the newest seek into a later chapter`() = runTest {
    val original = testBook()
    val latest =
      testBook(75_000)
        .content
        .copy(
          playbackSpeed = 1.75F,
          skipSilence = true,
          gain = 2F,
          lastPlayedAt = java.time.Instant.parse("2026-11-08T00:00:00Z"),
        )
    val contents = MemoryBookContentRepo(original.content)
    val trackedChapters = spyk(chapters)
    coEvery { trackedChapters.get(original.chapters.first().id) } coAnswers
      {
        contents.put(latest)
        original.chapters.first()
      }
    val replacement = "aud_cccccccc-3333-4333-8333-333333333333"
    val manifest =
      testManifest().let { old ->
        old.copy(
          assets =
            old.assets.map {
              if (it.sequence == 1) it.copy(assetId = replacement, fileId = 103) else it
            },
          chapters =
            old.chapters.map {
              if (it.assetId == voice.core.bookorbit.SECOND_ASSET_ID) it.copy(assetId = replacement)
              else it
            },
        )
      }
    coEvery { api.books(3, any()) } returns BookPage(listOf(audioCard()), total = 1)
    coEvery { api.manifest(42) } returns manifest

    LibrarySync(
        http,
        AccountStore(accounts),
        contents,
        updatingBookRepository(contents),
        trackedChapters,
        bookmarks,
        CachedBooksStore(cached),
        context,
      )
      .sync(account)

    val updated = assertNotNull(contents.get(original.id))
    assertEquals(manifest.chapters(account).last().id, updated.currentChapter)
    assertEquals(15_000L, updated.positionInChapter)
    assertEquals(latest.playbackSpeed, updated.playbackSpeed)
    assertEquals(latest.lastPlayedAt, updated.lastPlayedAt)
    assertEquals(latest.gain, updated.gain)
    assertEquals(latest.skipSilence, updated.skipSilence)
  }

  @Test
  fun `a failed cover reference update keeps the previous file`() = runTest {
    val cover = temporary.newFile("retained-cover.jpg")
    val original = testBook().content.let { it.copy(metadata = it.metadata.copy(cover = cover)) }
    val contents = MemoryBookContentRepo(original)
    coEvery { api.books(3, any()) } returns BookPage(listOf(audioCard()), total = 1)
    coEvery { api.manifest(42) } returns testManifest()
    val repository = updatingBookRepository(contents)
    coEvery { repository.updateBook(any(), any()) } coAnswers
      {
        val _ = secondArg<(voice.core.data.BookContent) -> voice.core.data.BookContent>()(original)
        throw IOException("storage unavailable")
      }

    assertFailsWith<IOException> {
      LibrarySync(
          http,
          AccountStore(accounts),
          contents,
          repository,
          chapters,
          bookmarks,
          CachedBooksStore(cached),
          context,
        )
        .sync(account)
    }

    assertEquals(cover, contents.get(original.id)!!.metadata.cover)
    assertTrue(cover.exists())
  }

  private fun audioCard(): BookCard =
    testCard().copy(files = listOf(BookFile(99, "epub"), BookFile(101, "mp3")))

  private fun sync(contents: MemoryBookContentRepo) =
    LibrarySync(
      http = http,
      accountStore = AccountStore(accounts),
      contentRepo = contents,
      bookRepository = updatingBookRepository(contents),
      chapterRepo = chapters,
      bookmarkRepo = bookmarks,
      cachedBooksStore = CachedBooksStore(cached),
      context = context,
    )

  private fun httpFailure(code: Int) =
    HttpException(Response.error<Unit>(code, "{}".toResponseBody()))
}
