package voice.core.bookorbit

import io.mockk.coEvery
import io.mockk.mockk
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository

internal fun updatingBookRepository(contents: BookContentRepo): BookRepository = mockk {
  coEvery { updateBook(any(), any()) } coAnswers
    {
      val id = firstArg<BookId>()
      val current = contents.get(id)
      if (current != null) {
        val updated = secondArg<(BookContent) -> BookContent>()(current)
        if (updated != current) contents.put(updated)
      }
    }
}
