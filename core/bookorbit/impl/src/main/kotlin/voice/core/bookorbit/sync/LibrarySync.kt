package voice.core.bookorbit.sync

import android.content.Context
import android.graphics.BitmapFactory
import dev.zacsweers.metro.Inject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import retrofit2.HttpException
import voice.core.bookorbit.BookOrbitIds
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.CachedBook
import voice.core.bookorbit.account.CachedBooksStore
import voice.core.bookorbit.api.AUDIO_FORMATS
import voice.core.bookorbit.api.AudiobookManifest
import voice.core.bookorbit.api.BookCard
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.hasAudio
import voice.core.bookorbit.bookRef
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.Chapter
import voice.core.data.CustomMetadata
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.BookmarkRepo
import voice.core.data.repo.ChapterRepo
import voice.core.logging.api.Logger

@Inject
internal class LibrarySync(
  private val http: BookOrbitHttp,
  private val accountStore: AccountStore,
  private val contentRepo: BookContentRepo,
  private val bookRepository: BookRepository,
  private val chapterRepo: ChapterRepo,
  private val bookmarkRepo: BookmarkRepo,
  private val cachedBooksStore: CachedBooksStore,
  private val context: Context,
) {
  suspend fun sync(account: Account) {
    val api = http.authenticatedApi(account)
    val cards =
      account.libraryIds.flatMap { allItems(api, it) }.filter { it.hasAudio }.distinctBy { it.id }
    val present =
      cards.map { BookOrbitIds.bookId(account.instanceId, account.userId, it.id) }.toSet()
    for (card in cards) {
      if (!account.sameOwner(accountStore.data.first())) return
      try {
        val key = BookOrbitIds.bookId(account.instanceId, account.userId, card.id).value
        val cached = cachedBooksStore.data.first()[key]
        val manifest =
          if (
            cached != null &&
              cached.card.updatedAt != null &&
              cached.card.updatedAt == card.updatedAt
          ) {
            cached.manifest
          } else {
            api.manifest(card.id)
          }
        store(account, card, manifest)
      } catch (e: CancellationException) {
        throw e
      } catch (_: HttpException) {
        Logger.w("Could not update an audiobook")
      } catch (_: SerializationException) {
        Logger.w("The server answered an audiobook in an unexpected way")
      } catch (_: IllegalArgumentException) {
        Logger.w("Skipping an audiobook with incomplete metadata")
      } catch (_: IllegalStateException) {
        Logger.w("Skipping an audiobook with incomplete metadata")
      }
    }
    if (!account.sameOwner(accountStore.data.first())) return
    contentRepo
      .all()
      .filter { account.owns(it.id.bookRef) }
      .forEach { content ->
        val active = content.id in present
        if (content.isActive != active) {
          bookRepository.updateBook(content.id) { it.copy(isActive = active) }
        }
      }
  }

  suspend fun refreshBook(
    account: Account,
    bookId: Long,
  ): AudiobookManifest {
    val id = BookOrbitIds.bookId(account.instanceId, account.userId, bookId)
    val cached =
      cachedBooksStore.data.first()[id.value] ?: throw IOException("Audiobook metadata unavailable")
    val manifest = http.authenticatedApi(account).manifest(bookId)
    try {
      store(account, cached.card, manifest)
    } catch (e: IllegalArgumentException) {
      throw IOException("Audiobook metadata unavailable", e)
    } catch (e: IllegalStateException) {
      throw IOException("Audiobook metadata unavailable", e)
    }
    return manifest
  }

  private suspend fun allItems(
    api: BookOrbitApi,
    libraryId: Long,
  ): List<BookCard> {
    val items = mutableListOf<BookCard>()
    val seen = mutableSetOf<Long>()
    var page = 0
    while (true) {
      val response =
        try {
          api.books(libraryId, bookQuery(page))
        } catch (e: HttpException) {
          if (e.code() == 403 || e.code() == 404) return emptyList()
          throw e
        }
      if (response.total < 0 || response.items.any { !seen.add(it.id) })
        throw IOException("Invalid library page")
      if (response.items.isEmpty() && items.size < response.total)
        throw IOException("Incomplete library page")
      items += response.items
      if (items.size >= response.total) return items
      if (response.items.size < PAGE_SIZE) throw IOException("Incomplete library page")
      if (++page > 100_000) throw IOException("Library page limit reached")
    }
  }

  private suspend fun store(
    account: Account,
    card: BookCard,
    manifest: AudiobookManifest,
  ) {
    require(manifest.book.id == card.id)
    val chapters = manifest.chapters(account)
    val id = BookOrbitIds.bookId(account.instanceId, account.userId, card.id)
    val existing = contentRepo.get(id)
    val previous = existing?.chapters?.mapNotNull { chapterRepo.get(it) }.orEmpty()
    val content = manifest.toBookContent(account, card, chapters, existing, previous)
    @Suppress("RETURN_VALUE_NOT_USED") Book(content, chapters, CustomMetadata(id))
    if (!account.sameOwner(accountStore.data.first())) return
    withContext(NonCancellable) {
      val oldChapters = previous.associateBy { it.id }
      chapters.filter { oldChapters[it.id] != it }.forEach { chapterRepo.put(it) }
      var removedCover: File? = null
      if (existing == null) {
        contentRepo.put(content)
      } else {
        bookRepository.updateBook(id) { current ->
          if (!card.hasCover) removedCover = current.metadata.cover
          manifest.toBookContent(account, card, chapters, current, previous)
        }
      }
      removedCover?.delete()
      if (existing != null && existing.chapters != content.chapters)
        moveBookmarks(existing, previous, chapters)
      cachedBooksStore.updateData { it + (id.value to CachedBook(manifest, card)) }
    }
    if (
      card.hasCover &&
        (content.metadata.cover?.name != coverFileName(account, card) ||
          content.metadata.cover?.isFile != true)
    ) {
      val cover = downloadCover(account, card) ?: return
      if (!account.sameOwner(accountStore.data.first())) return
      var previousCover: File? = null
      bookRepository.updateBook(id) { current ->
        previousCover = current.metadata.cover
        current.copy(metadata = current.metadata.copy(cover = cover))
      }
      if (previousCover != cover) previousCover?.delete()
    }
  }

  private suspend fun moveBookmarks(
    existing: BookContent,
    previous: List<Chapter>,
    chapters: List<Chapter>,
  ) {
    val ids = chapters.map { it.id }.toSet()
    bookmarkRepo
      .bookmarks(existing)
      .filter { it.chapterId !in ids }
      .forEach { bookmark ->
        val old = previous.positionInBook(bookmark.chapterId, bookmark.time) ?: return@forEach
        val position = chapters.positionAt(old)
        bookmarkRepo.addBookmark(
          bookmark.copy(chapterId = position.chapterId, time = position.positionInChapter),
        )
      }
  }

  private suspend fun downloadCover(
    account: Account,
    card: BookCard,
  ): File? =
    withContext(Dispatchers.IO) {
      val folder = File(context.filesDir, "bookCovers").apply { mkdirs() }
      val file = File(folder, coverFileName(account, card))
      if (file.exists()) return@withContext file
      val temporary = File.createTempFile("bo-cover-", ".tmp", folder)
      try {
        val request =
          Request.Builder()
            .url(
              "${account.serverUrl}api/v1/books/${card.id}/cover?medium=audio",
            )
            .tag(Account::class.java, account)
            .build()
        http.authenticatedClient.newCall(request).execute().use { response ->
          if (
            !response.isSuccessful || response.header("Content-Type")?.startsWith("image/") != true
          )
            return@withContext null
          response.body.byteStream().use { input ->
            temporary.outputStream().use { output ->
              val buffer = ByteArray(8192)
              var total = 0L
              while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 8 * 1024 * 1024) return@withContext null
                output.write(buffer, 0, count)
              }
            }
          }
          val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeFile(temporary.path, options)
          if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
          Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
          file
        }
      } catch (_: IOException) {
        null
      } finally {
        temporary.delete()
      }
    }

  private fun coverFileName(
    account: Account,
    card: BookCard,
  ): String {
    val version =
      MessageDigest.getInstance("SHA-256")
        .digest(card.coverVersion.toByteArray())
        .take(8)
        .joinToString("") {
          "%02x".format(it)
        }
    return "bo-${account.instanceId}-${account.userId}-${card.id}-$version.jpg"
  }
}

private const val PAGE_SIZE = 200

internal fun bookQuery(
  page: Int,
  size: Int = PAGE_SIZE,
): JsonObject = buildJsonObject {
  put(
    "filter",
    buildJsonObject {
      put("type", "group")
      put("join", "AND")
      put(
        "rules",
        buildJsonArray {
          add(
            buildJsonObject {
              put("type", "rule")
              put("field", "format")
              put("operator", "includesAny")
              put(
                "value",
                buildJsonArray {
                  AUDIO_FORMATS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                },
              )
            },
          )
        },
      )
    },
  )
  put(
    "sort",
    buildJsonArray {
      add(
        buildJsonObject {
          put("field", "title")
          put("dir", "asc")
        },
      )
    },
  )
  put(
    "pagination",
    buildJsonObject {
      put("page", page)
      put("size", size)
    },
  )
  put("collapseSeries", false)
}
