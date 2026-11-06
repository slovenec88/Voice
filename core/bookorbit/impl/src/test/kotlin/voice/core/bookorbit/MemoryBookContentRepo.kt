package voice.core.bookorbit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.repo.BookContentRepo

internal class MemoryBookContentRepo(vararg initial: BookContent) : BookContentRepo {

  private val values = MutableStateFlow(initial.toList())

  override fun flow(): Flow<List<BookContent>> = values

  override suspend fun all(): List<BookContent> = values.value

  override fun flow(id: BookId): Flow<BookContent?> = values.map { contents ->
    contents.find { it.id == id }
  }

  override suspend fun get(id: BookId): BookContent? = values.value.find { it.id == id }

  override suspend fun setAllInactiveExcept(ids: List<BookId>) {
    values.update { contents -> contents.map { it.copy(isActive = it.id in ids) } }
  }

  override suspend fun put(content: BookContent) {
    values.update { contents -> contents.filterNot { it.id == content.id } + content }
  }
}
