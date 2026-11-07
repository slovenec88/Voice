package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookMetadata
import voice.core.data.ChapterId
import voice.core.data.CustomMetadata
import voice.core.data.repo.internals.AppDb

@RunWith(AndroidJUnit4::class)
class BookRepositoryImplTest {

  private val db =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
      .allowMainThreadQueries()
      .build()
  private val contentRepo = BookContentRepoImpl(db.bookContentDao())
  private val customMetadataRepo = CustomMetadataRepoImpl(db.customMetadataDao())
  private val repo =
    BookRepositoryImpl(ChapterRepoImpl(db.chapterDao()), contentRepo, customMetadataRepo)

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `a name the files already have isn't kept, so the book follows its files`() = runTest {
    val content = content(name = "Dune", author = "Herbert")
    contentRepo.put(content)

    repo.updateCustomMetadata(content.id) { it.copy(name = "Dune", author = "Frank Herbert") }

    assertEquals(
      expected = CustomMetadata(content.id, author = "Frank Herbert"),
      actual = customMetadataRepo.get(content.id),
    )
  }

  @Test
  fun `a series the files already have isn't kept, so only the part is an edit`() = runTest {
    val content = content(name = "Dune", author = "Herbert", series = "Dune Chronicles", part = "1")
    contentRepo.put(content)

    repo.updateCustomMetadata(content.id) { it.copy(series = "Dune Chronicles", part = "2") }

    assertEquals(
      expected = CustomMetadata(content.id, part = "2"),
      actual = customMetadataRepo.get(content.id),
    )
  }

  @Test
  fun `a book that isn't in the library gets no edits`() = runTest {
    val id = BookId("dune")

    repo.updateCustomMetadata(id) { it.copy(name = "Dune Messiah") }

    assertEquals(expected = emptyList(), actual = db.customMetadataDao().all())
  }

  @Test
  fun `metadata and playback updates use the same lock and keep both changes`() = runTest {
    val initial = content(name = "Book", author = "Author")
    contentRepo.put(initial)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var first = true
    val gatedContents =
      mockk<BookContentRepo> {
        coEvery { get(initial.id) } coAnswers
          {
            if (first) {
              first = false
              entered.complete(Unit)
              release.await()
            }
            contentRepo.get(initial.id)
          }
        coEvery { put(any()) } coAnswers { contentRepo.put(firstArg()) }
      }
    val shared = BookRepositoryImpl(mockk(), gatedContents, mockk())
    val metadata = async {
      shared.updateBook(initial.id) { it.copy(metadata = it.metadata.copy(name = "Updated title")) }
    }
    entered.await()
    val playback = async { shared.updateBook(initial.id) { it.copy(positionInChapter = 12_000) } }
    runCurrent()
    assertFalse(playback.isCompleted)
    release.complete(Unit)
    metadata.await()
    playback.await()

    assertEquals(
      initial.copy(
        positionInChapter = 12_000,
        metadata = initial.metadata.copy(name = "Updated title"),
      ),
      contentRepo.get(initial.id),
    )
  }

  private fun content(
    name: String,
    author: String,
    series: String? = null,
    part: String? = null,
  ) =
    BookContent(
      id = BookId(name),
      playbackSpeed = 1F,
      skipSilence = false,
      isActive = true,
      lastPlayedAt = Instant.EPOCH,
      addedAt = Instant.EPOCH,
      chapters = listOf(ChapterId("chapter")),
      currentChapter = ChapterId("chapter"),
      positionInChapter = 0,
      gain = 0F,
      metadata =
        BookMetadata(
          name = name,
          author = author,
          album = null,
          title = null,
          genre = null,
          narrator = null,
          series = series,
          part = part,
          cover = null,
          fileLastModified = null,
        ),
    )
}
