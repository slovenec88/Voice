package voice.core.bookorbit.sync

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import voice.core.bookorbit.MemoryBookRepository
import voice.core.bookorbit.MemoryBookmarkRepo
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.SyncedBookmark
import voice.core.bookorbit.account.SyncedBookmarksStore
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.BookOrbitBookmark
import voice.core.bookorbit.api.BookmarkUpdate
import voice.core.bookorbit.bookOrbitJson
import voice.core.bookorbit.fixture
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testBook
import voice.core.data.BookId
import voice.core.data.Bookmark
import voice.core.data.CustomMetadata

class BookmarkSyncTest {

  private val account = testAccount()
  private val book = testBook()
  private val accounts = MemoryDataStore<Account?>(account)
  private val baseline = MemoryDataStore<Map<String, Map<String, SyncedBookmark>>>(emptyMap())
  private val remote = bookOrbitJson.decodeFromString<List<BookOrbitBookmark>>(fixture("bookmarks"))
  private val api = mockk<BookOrbitApi> { coEvery { bookmarks(42) } returns emptyList() }
  private val http = mockk<BookOrbitHttp> { every { authenticatedApi(account) } returns api }

  @Test
  fun `distinct remote UUIDs at the same position import separately`() = runTest {
    val bookmarks = MemoryBookmarkRepo()
    coEvery { api.bookmarks(42) } returns remote

    sync(bookmarks).sync(account)

    val imported = bookmarks.bookmarks(book.content)
    assertEquals(remote.map { it.id }.toSet(), imported.map { it.id.value.toString() }.toSet())
    assertEquals(listOf(15_000L, 15_000L), imported.map { it.time })
    assertTrue(imported.all { it.chapterId == book.chapters[1].id })
    assertEquals(2, baseline.data.first().getValue(book.id.value).size)
  }

  @Test
  fun `a local bookmark uses its original UUID and chapter fallback title`() = runTest {
    val local = local(title = null)
    val bookmarks = MemoryBookmarkRepo(local)
    coEvery { api.createBookmark(42, any()) } returns Response.success(remote[0])

    sync(bookmarks).sync(account)

    coVerify(exactly = 1) {
      val _ =
        api.createBookmark(
          42,
          match {
            it.clientId == local.id.value.toString() &&
              it.positionMs == 75_000L &&
              it.title == "The Long Chapter"
          },
        )
    }
    assertEquals(null, bookmarks.bookmarks(book.content).single().title)
  }

  @Test
  fun `an uncertain create discovers the same UUID on the next list instead of duplicating it`() =
    runTest {
      val local = local(title = remote[0].title)
      val bookmarks = MemoryBookmarkRepo(local)
      coEvery { api.createBookmark(42, any()) } returns Response.error(503, "{}".toResponseBody())
      val sync = sync(bookmarks)
      sync.sync(account)
      coEvery { api.bookmarks(42) } returns listOf(remote[0])

      sync.sync(account)

      coVerify(exactly = 1) {
        val _ = api.createBookmark(42, any())
      }
      assertEquals(1, bookmarks.bookmarks(book.content).size)
      assertEquals(remote[0].id, bookmarks.bookmarks(book.content).single().id.value.toString())
      assertEquals(
        SyncedBookmark(remote[0].title, 75_000),
        baseline.data.first().getValue(book.id.value).getValue(remote[0].id),
      )
    }

  @Test
  fun `a remote rename changes the local title but keeps the local bookmark kind`() = runTest {
    val local = local("Old").copy(kind = Bookmark.Kind.Quote)
    val bookmarks = MemoryBookmarkRepo(local)
    agreed(local, "Old")
    coEvery { api.bookmarks(42) } returns listOf(remote[0].copy(title = "Renamed on web"))

    sync(bookmarks).sync(account)

    val renamed = bookmarks.bookmarks(book.content).single()
    assertEquals(local.id, renamed.id)
    assertEquals("Renamed on web", renamed.title)
    assertEquals(Bookmark.Kind.Quote, renamed.kind)
    coVerify(exactly = 0) {
      val _ = api.updateBookmark(any(), any(), any())
    }
  }

  @Test
  fun `a local rename sends only its title leaving server notes untouched`() = runTest {
    val local = local("Renamed in Voice")
    val bookmarks = MemoryBookmarkRepo(local)
    agreed(local, "Old")
    coEvery { api.bookmarks(42) } returns listOf(remote[0].copy(title = "Old"))
    coEvery { api.updateBookmark(42, remote[0].id, any()) } returns
      Response.success(remote[0].copy(title = "Renamed in Voice"))

    sync(bookmarks).sync(account)

    coVerify(exactly = 1) {
      val _ = api.updateBookmark(42, remote[0].id, BookmarkUpdate("Renamed in Voice"))
    }
    assertTrue(remote[0].note!!.contains("server-only note"))
    assertEquals(
      "Renamed in Voice",
      baseline.data.first().getValue(book.id.value).getValue(remote[0].id).title,
    )
  }

  @Test
  fun `simultaneous renames keep the local edit`() = runTest {
    val local = local("Here")
    val bookmarks = MemoryBookmarkRepo(local)
    agreed(local, "Old")
    coEvery { api.bookmarks(42) } returns listOf(remote[0].copy(title = "There"))
    coEvery { api.updateBookmark(42, remote[0].id, any()) } returns
      Response.success(remote[0].copy(title = "Here"))

    sync(bookmarks).sync(account)

    assertEquals("Here", bookmarks.bookmarks(book.content).single().title)
    coVerify(exactly = 1) {
      val _ = api.updateBookmark(42, remote[0].id, BookmarkUpdate("Here"))
    }
  }

  @Test
  fun `an offline local deletion keeps its baseline and is never reimported`() = runTest {
    val local = local(remote[0].title)
    agreed(local, local.title!!)
    val bookmarks = MemoryBookmarkRepo()
    coEvery { api.bookmarks(42) } returns listOf(remote[0])
    coEvery { api.deleteBookmark(42, remote[0].id) } returns
      Response.error(503, "{}".toResponseBody()) andThen
      Response.success(Unit)

    sync(bookmarks).sync(account)
    assertEquals(emptyList(), bookmarks.bookmarks(book.content))
    assertTrue(remote[0].id in baseline.data.first().getValue(book.id.value))
    sync(bookmarks).sync(account)

    assertEquals(emptyList(), bookmarks.bookmarks(book.content))
    assertFalse(remote[0].id in baseline.data.first().getValue(book.id.value))
    coVerify(exactly = 2) {
      val _ = api.deleteBookmark(42, remote[0].id)
    }
  }

  @Test
  fun `an already deleted remote bookmark acknowledges local deletion`() = runTest {
    val local = local(remote[0].title)
    agreed(local, local.title!!)
    coEvery { api.bookmarks(42) } returns listOf(remote[0])
    coEvery { api.deleteBookmark(42, remote[0].id) } returns
      Response.error(404, "{}".toResponseBody())

    sync(MemoryBookmarkRepo()).sync(account)

    assertEquals(emptyMap(), baseline.data.first().getValue(book.id.value))
  }

  @Test
  fun `a remote deletion removes only an unchanged local bookmark`() = runTest {
    val unchanged = local("Old")
    agreed(unchanged, "Old")
    val bookmarks = MemoryBookmarkRepo(unchanged)

    sync(bookmarks).sync(account)

    assertEquals(emptyList(), bookmarks.bookmarks(book.content))
    assertEquals(emptyMap(), baseline.data.first().getValue(book.id.value))
  }

  @Test
  fun `a remote deletion wins over a local rename whose UUID remains tombstoned`() = runTest {
    val edited = local("Keep my change")
    agreed(edited, "Old")
    val bookmarks = MemoryBookmarkRepo(edited)

    sync(bookmarks).sync(account)

    assertEquals(emptyList(), bookmarks.bookmarks(book.content))
    assertEquals(emptyMap(), baseline.data.first().getValue(book.id.value))
    coVerify(exactly = 0) {
      val _ = api.createBookmark(any(), any())
    }
  }

  @Test
  fun `a same position create conflict adopts the canonical UUID after preserving the local edit`() =
    runTest {
      val local =
        local(
            "Local note",
          )
          .copy(
            id = Bookmark.Id(Uuid.parse(remote[1].id)),
            kind = Bookmark.Kind.Quote,
            addedAt = Instant.parse("2026-10-30T10:00:00Z"),
          )
      val bookmarks = MemoryBookmarkRepo(local)
      var canonical = remote[0].copy(title = "Server note")
      var lists = 0
      coEvery { api.bookmarks(42) } coAnswers
        {
          if (++lists == 1) emptyList() else listOf(canonical)
        }
      coEvery { api.createBookmark(42, any()) } returns Response.error(409, "{}".toResponseBody())
      coEvery { api.updateBookmark(42, canonical.id, any()) } coAnswers
        {
          canonical = canonical.copy(title = thirdArg<BookmarkUpdate>().title)
          Response.success(canonical)
        }
      val sync = sync(bookmarks)

      sync.sync(account)
      sync.sync(account)

      val merged = bookmarks.bookmarks(book.content).single()
      assertEquals(canonical.id, merged.id.value.toString())
      assertEquals("Local note", merged.title)
      assertEquals(Bookmark.Kind.Quote, merged.kind)
      assertTrue(canonical.note!!.contains("server-only note"))
      coVerify(exactly = 1) {
        val _ = api.createBookmark(42, match { it.clientId == local.id.value.toString() })
      }
      coVerify(exactly = 1) {
        val _ = api.updateBookmark(42, canonical.id, BookmarkUpdate("Local note"))
      }
      assertEquals(setOf(canonical.id), baseline.data.first().getValue(book.id.value).keys)
    }

  @Test
  fun `a failed list cannot look like deletion of all remote bookmarks`() = runTest {
    val local = local("Old")
    agreed(local, "Old")
    val bookmarks = MemoryBookmarkRepo(local)
    coEvery { api.bookmarks(42) } throws IOException("offline")

    assertFailsWith<IOException> { sync(bookmarks).sync(account) }

    assertEquals(listOf(local), bookmarks.bookmarks(book.content))
    assertEquals(1, baseline.data.first().getValue(book.id.value).size)
  }

  @Test
  fun `duplicate server UUIDs are rejected without losing local bookmarks`() = runTest {
    val local = local("Old")
    val bookmarks = MemoryBookmarkRepo(local)
    coEvery { api.bookmarks(42) } returns listOf(remote[0], remote[0])

    assertFailsWith<IOException> { sync(bookmarks).sync(account) }

    assertEquals(listOf(local), bookmarks.bookmarks(book.content))
  }

  @Test
  fun `another providers book never enters BookOrbit bookmark synchronization`() = runTest {
    val absId = BookId("abs://item/existing")
    val absBook = book.copy(content = book.content.copy(id = absId), custom = CustomMetadata(absId))

    sync(MemoryBookmarkRepo()).sync(account, listOf(absBook))

    coVerify(exactly = 0) {
      val _ = api.bookmarks(any())
    }
  }

  @Test
  fun `a server UUID cannot overwrite a bookmark belonging to another book`() = runTest {
    val foreignBook = book.content.copy(id = BookId("content://device/another-book"))
    val foreign = local("A local note").copy(bookId = foreignBook.id)
    val bookmarks = MemoryBookmarkRepo(foreign)
    coEvery { api.bookmarks(42) } returns listOf(remote[0])

    assertFailsWith<IOException> { sync(bookmarks).sync(account) }

    assertEquals(listOf(foreign), bookmarks.bookmarks(foreignBook))
    assertEquals(emptyList(), bookmarks.bookmarks(book.content))
    assertEquals(emptyMap(), baseline.data.first())
  }

  @Test
  fun `nudging a synced bookmark replaces its UUID and preserves server notes across retry`() =
    runTest {
      val original = remote[0].copy(note = "A server-only note")
      val here = local(original.title).copy(time = 10_000)
      val bookmarks = MemoryBookmarkRepo(here)
      agreed(here, original.title)
      var replacement: BookOrbitBookmark? = null
      coEvery { api.bookmarks(42) } answers { listOfNotNull(original, replacement) }
      coEvery { api.createBookmark(42, any()) } answers
        {
          val request = secondArg<voice.core.bookorbit.api.BookmarkRequest>()
          replacement =
            original.copy(
              id = request.clientId,
              positionMs = request.positionMs,
              title = request.title,
              note = request.note,
            )
          Response.success(replacement)
        }
      coEvery { api.deleteBookmark(42, original.id) } returns
        Response.error(503, "{}".toResponseBody()) andThen
        Response.success(Unit)
      sync(bookmarks).sync(account)
      val newId =
        baseline.data.first().getValue(book.id.value).getValue(original.id).replacement!!.clientId

      sync(bookmarks).sync(account)

      coVerify(exactly = 1) {
        val _ =
          api.createBookmark(
            42,
            match {
              it.clientId == newId && it.positionMs == 70_000L && it.note == original.note
            },
          )
      }
      assertEquals(newId, bookmarks.bookmarks(book.content).single().id.value.toString())
      assertEquals(10_000L, bookmarks.bookmarks(book.content).single().time)
      assertFalse(original.id in baseline.data.first().getValue(book.id.value))
    }

  @Test
  fun `a nudged bookmark adopts a destination collision without losing either note`() = runTest {
    val original = remote[0].copy(note = "Original note")
    val destination = remote[1].copy(positionMs = 70_000, note = "Destination note")
    val here = local(original.title).copy(time = 10_000)
    val bookmarks = MemoryBookmarkRepo(here)
    agreed(here, original.title)
    coEvery { api.bookmarks(42) } returns listOf(original, destination)
    coEvery { api.createBookmark(42, any()) } returns Response.error(409, "{}".toResponseBody())
    coEvery { api.updateBookmark(42, any(), any()) } returns Response.success(destination)
    coEvery { api.deleteBookmark(42, original.id) } returns Response.success(Unit)

    sync(bookmarks).sync(account)

    assertEquals(destination.id, bookmarks.bookmarks(book.content).single().id.value.toString())
    coVerify {
      val _ =
        api.updateBookmark(
          42,
          destination.id,
          match { it.note == "Destination note\n\nOriginal note" },
        )
    }
    assertFalse(original.id in baseline.data.first().getValue(book.id.value))
  }

  @Test
  fun `deleting a nudge whose create response was lost does not resurrect its replacement`() =
    runTest {
      val original = remote[0]
      val here = local(original.title).copy(time = 10_000)
      val bookmarks = MemoryBookmarkRepo(here)
      agreed(here, original.title)
      var replacement: BookOrbitBookmark? = null
      coEvery { api.bookmarks(42) } answers { listOfNotNull(original, replacement) }
      coEvery { api.createBookmark(42, any()) } answers
        {
          val request = secondArg<voice.core.bookorbit.api.BookmarkRequest>()
          replacement = original.copy(id = request.clientId, positionMs = request.positionMs)
          throw IOException("Lost response")
        }
      coEvery { api.deleteBookmark(42, any()) } returns Response.success(Unit)
      assertFailsWith<IOException> { sync(bookmarks).sync(account) }
      bookmarks.deleteBookmark(here.id)

      sync(bookmarks).sync(account)

      assertEquals(emptyList(), bookmarks.bookmarks(book.content))
      coVerify(exactly = 1) {
        val _ = api.deleteBookmark(42, replacement!!.id)
      }
    }

  @Test
  fun `a replacement response cannot overwrite another books bookmark`() = runTest {
    val here = local(remote[0].title).copy(time = 10_000)
    val foreign =
      local("Keep this")
        .copy(bookId = BookId("content://other/book"), id = Bookmark.Id(Uuid.parse(remote[1].id)))
    val bookmarks = MemoryBookmarkRepo(here, foreign)
    agreed(here, remote[0].title)
    coEvery { api.bookmarks(42) } returns listOf(remote[0])
    coEvery { api.createBookmark(42, any()) } returns
      Response.success(remote[1].copy(positionMs = 70_000))

    assertFailsWith<IOException> { sync(bookmarks).sync(account) }

    assertEquals(listOf(foreign), bookmarks.bookmarks(book.content.copy(id = foreign.bookId)))
    coVerify(exactly = 0) {
      val _ = api.deleteBookmark(any(), any())
    }
  }

  @Test
  fun `a newer nudge made during a replacement request survives the acknowledgement`() = runTest {
    val here = local(remote[0].title).copy(time = 10_000)
    val bookmarks = MemoryBookmarkRepo(here)
    agreed(here, remote[0].title)
    coEvery { api.bookmarks(42) } returns listOf(remote[0])
    coEvery { api.createBookmark(42, any()) } coAnswers
      {
        val request = secondArg<voice.core.bookorbit.api.BookmarkRequest>()
        bookmarks.addBookmark(here.copy(time = 5_000))
        Response.success(remote[0].copy(id = request.clientId, positionMs = request.positionMs))
      }
    coEvery { api.deleteBookmark(42, any()) } returns Response.success(Unit)

    sync(bookmarks).sync(account)

    val latest = bookmarks.bookmarks(book.content).single()
    assertEquals(5_000L, latest.time)
    assertEquals(
      70_000L,
      baseline.data.first().getValue(book.id.value).getValue(latest.id.value.toString()).positionMs,
    )
  }

  @Test
  fun `deleting a bookmark during its replacement request queues cleanup without readding it`() =
    runTest {
      val here = local(remote[0].title).copy(time = 10_000)
      val bookmarks = MemoryBookmarkRepo(here)
      agreed(here, remote[0].title)
      var original: BookOrbitBookmark? = remote[0]
      var replacement: BookOrbitBookmark? = null
      coEvery { api.bookmarks(42) } answers { listOfNotNull(original, replacement) }
      coEvery { api.createBookmark(42, any()) } coAnswers
        {
          val request = secondArg<voice.core.bookorbit.api.BookmarkRequest>()
          replacement = remote[0].copy(id = request.clientId, positionMs = request.positionMs)
          bookmarks.deleteBookmark(here.id)
          Response.success(replacement)
        }
      coEvery { api.deleteBookmark(42, any()) } answers
        {
          if (secondArg<String>() == remote[0].id) original = null else replacement = null
          Response.success(Unit)
        }

      sync(bookmarks).sync(account)
      assertEquals(emptyList(), bookmarks.bookmarks(book.content))
      assertEquals(1, baseline.data.first().getValue(book.id.value).size)
      sync(bookmarks).sync(account)

      assertEquals(emptyList(), bookmarks.bookmarks(book.content))
      assertEquals(null, replacement)
    }

  private fun sync(bookmarks: MemoryBookmarkRepo) =
    BookmarkSync(
      http,
      AccountStore(accounts),
      MemoryBookRepository(book),
      bookmarks,
      SyncedBookmarksStore(baseline),
    )

  private suspend fun agreed(
    bookmark: Bookmark,
    title: String,
  ) {
    baseline.updateData {
      mapOf(book.id.value to mapOf(bookmark.id.value.toString() to SyncedBookmark(title, 75_000)))
    }
  }

  private fun local(title: String?) =
    Bookmark(
      book.id,
      book.chapters[1].id,
      title,
      15_000,
      Instant.EPOCH,
      Bookmark.Id(Uuid.parse(remote[0].id)),
    )
}
