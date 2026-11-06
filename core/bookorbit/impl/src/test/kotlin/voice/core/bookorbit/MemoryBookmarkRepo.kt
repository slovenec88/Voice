package voice.core.bookorbit

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.Bookmark
import voice.core.data.repo.BookmarkRepo

internal class MemoryBookmarkRepo(vararg initial: Bookmark) : BookmarkRepo {

  private val values = MutableStateFlow(initial.toList())

  override suspend fun deleteBookmark(id: Bookmark.Id) {
    values.update { bookmarks -> bookmarks.filterNot { it.id == id } }
  }

  override suspend fun addBookmark(bookmark: Bookmark) {
    values.update { bookmarks -> bookmarks.filterNot { it.id == bookmark.id } + bookmark }
  }

  override suspend fun addBookmarkAtBookPosition(
    book: Book,
    title: String?,
  ): Bookmark {
    val bookmark =
      Bookmark(
        book.id,
        book.currentChapter.id,
        title,
        book.content.positionInChapter,
        Instant.EPOCH,
        Bookmark.Id.random(),
      )
    addBookmark(bookmark)
    return bookmark
  }

  override suspend fun bookmarks(book: BookContent): List<Bookmark> =
    values.value.filter { it.bookId == book.id }

  override fun bookmarksFlow(book: BookContent): Flow<List<Bookmark>> = values.map { bookmarks ->
    bookmarks.filter { it.bookId == book.id }
  }

  override fun flow(): Flow<List<Bookmark>> = values
}
