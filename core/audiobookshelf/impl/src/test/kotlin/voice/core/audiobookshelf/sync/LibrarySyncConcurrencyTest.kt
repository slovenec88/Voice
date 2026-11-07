package voice.core.audiobookshelf.sync

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import voice.core.audiobookshelf.account.Account
import voice.core.audiobookshelf.api.AbsBookMetadata
import voice.core.audiobookshelf.api.AudiobookshelfApi
import voice.core.audiobookshelf.api.BatchGetResponse
import voice.core.audiobookshelf.api.LibraryItemsResponse
import voice.core.audiobookshelf.http.AudiobookshelfHttp
import voice.core.data.BookContent
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ChapterRepo

class LibrarySyncConcurrencyTest {
  @Test
  fun `a metadata update keeps playback saved during chapter reads`() = runTest {
    val item = item(tracks = listOf(track(1, 0.0, 60.0, "audio.mp3")))
    val chapters = item.chapters()
    val initial = item.toBookContent(chapters, null)
    val latest =
      initial.copy(
        positionInChapter = 12_000,
        playbackSpeed = 1.75F,
        skipSilence = true,
        gain = 2F,
        lastPlayedAt = Instant.parse("2026-11-08T00:00:00Z"),
      )
    var stored = initial
    val contents =
      mockk<BookContentRepo> {
        coEvery { all() } answers { listOf(stored) }
        coEvery { get(initial.id) } answers { stored }
        coEvery { put(any()) } answers { stored = firstArg() }
      }
    val books =
      mockk<BookRepository> {
        coEvery { updateBook(initial.id, any()) } answers
          {
            stored = secondArg<(BookContent) -> BookContent>()(stored)
          }
      }
    var reads = 0
    val chapterRepo =
      mockk<ChapterRepo>(relaxUnitFun = true) {
        coEvery { get(any<voice.core.data.ChapterId>()) } coAnswers
          {
            if (++reads == 2) stored = latest
            chapters.first()
          }
      }
    val changed =
      item.copy(
        updatedAt = item.updatedAt + 1,
        media = item.media.copy(metadata = AbsBookMetadata(title = "Updated title")),
      )
    val api =
      mockk<AudiobookshelfApi> {
        coEvery { libraryItems(any(), any(), any(), any()) } returns
          LibraryItemsResponse(listOf(changed), 1)
        coEvery { items(any()) } returns BatchGetResponse(listOf(changed))
      }
    val http = mockk<AudiobookshelfHttp> { every { authenticatedApi(any()) } returns api }
    val account =
      Account(
        "https://example.org/",
        "user",
        "listener",
        "access",
        "refresh",
        listOf("library"),
        "2.37.1",
      )

    LibrarySync(
        http,
        contents,
        books,
        chapterRepo,
        mockk {
          coEvery { bookmarks(any()) } returns emptyList()
        },
        mockk<Context>(),
      )
      .sync(account)

    assertEquals("Updated title", stored.metadata.name)
    assertEquals(2, reads)
    assertEquals(latest.copy(metadata = stored.metadata), stored)
  }
}
