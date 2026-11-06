package voice.core.bookorbit.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.SyncedBookmark
import voice.core.bookorbit.account.SyncedBookmarksStore
import voice.core.bookorbit.api.BookOrbitBookmark
import voice.core.bookorbit.api.BookmarkRequest
import voice.core.bookorbit.api.BookmarkUpdate
import voice.core.bookorbit.bookRef
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.data.Book
import voice.core.data.Bookmark
import voice.core.data.markForPosition
import voice.core.data.repo.BookRepository
import voice.core.data.repo.BookmarkRepo

@SingleIn(AppScope::class)
@Inject
internal class BookmarkSync(
  private val http: BookOrbitHttp,
  private val accountStore: AccountStore,
  private val bookRepository: BookRepository,
  private val bookmarkRepo: BookmarkRepo,
  private val syncedBookmarksStore: SyncedBookmarksStore,
) {
  private val mutex = Mutex()

  suspend fun sync(
    account: Account,
    books: List<Book>? = null,
  ) = mutex.withLock {
    val api = http.authenticatedApi(account)
    for (book in (books ?: bookRepository.all()).filter { account.owns(it.id.bookRef) }) {
      if (!account.sameOwner(accountStore.data.first())) return@withLock
      val ref = book.id.bookRef ?: continue
      val server = api.bookmarks(ref.bookId)
      if (
        server.map { it.id }.distinct().size != server.size ||
          server.any {
            it.bookId != ref.bookId ||
              it.positionMs < 0 ||
              runCatching { Uuid.parse(it.id) }.isFailure
          }
      ) {
        throw IOException("Invalid audiobook bookmarks")
      }
      if (!account.sameOwner(accountStore.data.first())) return@withLock
      val local =
        bookmarkRepo.bookmarks(book.content).associateBy { it.id.value.toString() }.toMutableMap()
      val remote = server.associateBy { it.id }.toMutableMap()
      val baseline = syncedBookmarksStore.data.first()[book.id.value].orEmpty().toMutableMap()
      val protected = mutableSetOf<String>()
      val otherBooks =
        bookmarkRepo
          .flow()
          .first()
          .filter { it.bookId != book.id }
          .map { it.id.value.toString() }
          .toSet()
      if (server.any { it.id in otherBooks })
        throw IOException("Bookmark identifier belongs to another book")
      baseline.toMap().forEach { (id, agreed) ->
        val replacement = agreed.replacement
        if (replacement != null && id !in local) {
          baseline[replacement.clientId] = SyncedBookmark(replacement.title, replacement.positionMs)
        }
      }
      syncedBookmarksStore.updateData { it + (book.id.value to baseline.toMap()) }

      suspend fun mergeCollision(
        here: Bookmark,
        canonical: BookOrbitBookmark,
      ): Boolean {
        protected += canonical.id
        val existing = local[canonical.id]
        val newest = existing?.takeIf { it.addedAt >= here.addedAt } ?: here
        val title = newest.title?.takeIf { it.isNotBlank() }?.take(500) ?: canonical.title
        if (
          title != canonical.title &&
            !api.updateBookmark(ref.bookId, canonical.id, BookmarkUpdate(title)).isSuccessful
        )
          return false
        if (!account.sameOwner(accountStore.data.first())) return false
        val merged =
          book
            .bookmarkAt(canonical)
            .copy(title = title, kind = newest.kind, addedAt = newest.addedAt)
        bookmarkRepo.addBookmark(merged)
        if (here.id != merged.id) bookmarkRepo.deleteBookmark(here.id)
        local.remove(here.id.value.toString())
        local[canonical.id] = merged
        remote[canonical.id] = canonical.copy(title = title)
        baseline.remove(here.id.value.toString())
        baseline[canonical.id] = SyncedBookmark(title, canonical.positionMs)
        return true
      }

      val order =
        local.values.sortedByDescending { it.addedAt }.map { it.id.value.toString() } +
          remote.keys +
          baseline.keys
      for (id in order.distinct()) {
        if (!account.sameOwner(accountStore.data.first())) return@withLock
        val here = local[id]
        val there = remote[id]
        val last = baseline[id]
        val position = here?.let { book.chapters.positionInBook(it.chapterId, it.time) }
        if (
          here != null &&
            position != null &&
            (last?.replacement != null ||
              (there != null && position != there.positionMs && position != last?.positionMs))
        ) {
          val request =
            last?.replacement
              ?: BookmarkRequest(
                Uuid.random().toString(),
                position,
                book.titleOf(here),
                note = there?.note,
              )
          baseline[id] =
            SyncedBookmark(
              last?.title ?: there!!.title,
              last?.positionMs ?: there!!.positionMs,
              request,
            )
          syncedBookmarksStore.updateData { it + (book.id.value to baseline.toMap()) }
          var replacement = remote[request.clientId]
          if (replacement == null) {
            val created = api.createBookmark(ref.bookId, request)
            if (created.isSuccessful) {
              replacement = created.body()
              if (replacement != null && replacement.id != request.clientId)
                throw IOException("Unexpected replacement identifier")
            } else if (created.code() == 409) {
              val candidates = api.bookmarks(ref.bookId)
              if (
                candidates.any {
                  it.bookId != ref.bookId ||
                    it.id in otherBooks ||
                    runCatching { Uuid.parse(it.id) }.isFailure
                }
              ) {
                throw IOException("Invalid replacement bookmarks")
              }
              replacement =
                candidates.firstOrNull { it.id == request.clientId }
                  ?: candidates.firstOrNull { it.positionMs == request.positionMs }
            }
          }
          if (replacement == null || !account.sameOwner(accountStore.data.first())) continue
          val replacementId = replacement.id
          if (
            replacement.bookId != ref.bookId ||
              replacement.positionMs != request.positionMs ||
              replacementId in otherBooks ||
              runCatching { Uuid.parse(replacementId) }.isFailure
          ) {
            throw IOException("Unexpected replacement bookmark")
          }
          if (replacement.id != request.clientId) {
            val oldNote = replacement.note
            val note =
              request.note
                ?.takeIf { it.isNotBlank() && oldNote?.contains(it) != true }
                ?.let {
                  listOfNotNull(oldNote?.takeIf(String::isNotBlank), it).joinToString("\n\n")
                }
            if (
              !api
                .updateBookmark(ref.bookId, replacement.id, BookmarkUpdate(request.title, note))
                .isSuccessful
            )
              continue
            replacement = replacement.copy(title = request.title, note = note ?: oldNote)
          }
          val deleted = api.deleteBookmark(ref.bookId, id)
          if (
            (!deleted.isSuccessful && deleted.code() != 404) ||
              !account.sameOwner(accountStore.data.first())
          )
            continue
          val latest = bookmarkRepo.bookmarks(book.content).firstOrNull { it.id == here.id }
          if (latest == null) {
            baseline.remove(id)
            if (replacement.id == request.clientId)
              baseline[replacement.id] = SyncedBookmark(replacement.title, replacement.positionMs)
            syncedBookmarksStore.updateData { it + (book.id.value to baseline.toMap()) }
            break
          }
          val moved =
            book
              .bookmarkAt(replacement)
              .copy(
                chapterId = latest.chapterId,
                time = latest.time,
                title = latest.title,
                kind = latest.kind,
                addedAt = latest.addedAt,
              )
          bookmarkRepo.addBookmark(moved)
          bookmarkRepo.deleteBookmark(here.id)
          local.remove(id)
          local[replacement.id] = moved
          remote.remove(id)
          remote[replacement.id] = replacement
          protected += replacement.id
          baseline.remove(id)
          baseline[replacement.id] = SyncedBookmark(replacement.title, replacement.positionMs)
          syncedBookmarksStore.updateData { it + (book.id.value to baseline.toMap()) }
          continue
        }
        when {
          here == null && there != null && last != null -> {
            if (id in protected) continue
            val result = api.deleteBookmark(ref.bookId, id)
            if (result.isSuccessful || result.code() == 404) baseline.remove(id)
          }
          here == null && there != null -> {
            bookmarkRepo.addBookmark(book.bookmarkAt(there))
            baseline[id] = SyncedBookmark(there.title, there.positionMs)
          }
          here != null && there == null -> {
            if (last != null) {
              bookmarkRepo.deleteBookmark(here.id)
              baseline.remove(id)
            } else {
              val title = book.titleOf(here)
              val position = book.chapters.positionInBook(here.chapterId, here.time) ?: continue
              val canonical = remote.values.firstOrNull { it.positionMs == position }
              if (canonical != null) {
                val _ = mergeCollision(here, canonical)
              } else {
                val response = api.createBookmark(ref.bookId, BookmarkRequest(id, position, title))
                if (response.isSuccessful) {
                  baseline[id] = SyncedBookmark(title, position)
                  response.body()?.let { remote[id] = it }
                } else if (response.code() == 409) {
                  val fresh = api.bookmarks(ref.bookId)
                  if (
                    fresh.any {
                      it.bookId != ref.bookId ||
                        it.positionMs < 0 ||
                        it.id in otherBooks ||
                        runCatching { Uuid.parse(it.id) }.isFailure
                    }
                  ) {
                    throw IOException("Invalid audiobook bookmarks")
                  }
                  val collision = fresh.firstOrNull { it.positionMs == position }
                  if (collision != null) {
                    val _ = mergeCollision(here, collision)
                  } else {
                    bookmarkRepo.deleteBookmark(here.id)
                    baseline.remove(id)
                  }
                }
              }
            }
          }
          here != null && there != null -> {
            val title = book.titleOf(here)
            val agreed =
              when {
                title == there.title -> title
                last != null && title == last.title -> {
                  bookmarkRepo.addBookmark(here.copy(title = there.title))
                  there.title
                }
                else -> {
                  if (!api.updateBookmark(ref.bookId, id, BookmarkUpdate(title)).isSuccessful)
                    continue
                  title
                }
              }
            baseline[id] = SyncedBookmark(agreed, there.positionMs)
          }
          else -> baseline.remove(id)
        }
        if (!account.sameOwner(accountStore.data.first())) return@withLock
        syncedBookmarksStore.updateData { it + (book.id.value to baseline.toMap()) }
      }
    }
  }
}

private fun Book.bookmarkAt(remote: BookOrbitBookmark): Bookmark {
  val position = positionAt(remote.positionMs)
  return Bookmark(
    id,
    position.chapterId,
    remote.title,
    position.positionInChapter,
    instant(remote.createdAt),
    Bookmark.Id(Uuid.parse(remote.id)),
  )
}

private fun Book.titleOf(bookmark: Bookmark): String {
  val chapter = chapters.firstOrNull { it.id == bookmark.chapterId }
  return (bookmark.title?.takeIf { it.isNotBlank() }
      ?: chapter?.markForPosition(bookmark.time)?.name?.takeIf { it.isNotBlank() }
      ?: chapter?.name?.takeIf { it.isNotBlank() }
      ?: name)
    .take(500)
    .ifBlank { "Bookmark" }
}
