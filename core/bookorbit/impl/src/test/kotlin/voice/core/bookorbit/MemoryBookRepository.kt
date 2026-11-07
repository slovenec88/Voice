package voice.core.bookorbit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.CustomMetadata
import voice.core.data.repo.BookRepository

internal class MemoryBookRepository(vararg initial: Book) : BookRepository {

  private val books = MutableStateFlow(initial.toList())

  override fun flow(): Flow<List<Book>> = books

  override suspend fun all(): List<Book> = books.value

  override fun flow(id: BookId): Flow<Book?> = books.map { values -> values.find { it.id == id } }

  override suspend fun get(id: BookId): Book? = books.value.find { it.id == id }

  override suspend fun updateBook(
    id: BookId,
    update: (BookContent) -> BookContent,
  ) {
    books.update { values -> values.map { if (it.id == id) it.update(update) else it } }
  }

  override suspend fun updateCustomMetadata(
    id: BookId,
    update: (CustomMetadata) -> CustomMetadata,
  ) {
    books.update { values ->
      values.map { if (it.id == id) it.copy(custom = update(it.custom)) else it }
    }
  }
}
