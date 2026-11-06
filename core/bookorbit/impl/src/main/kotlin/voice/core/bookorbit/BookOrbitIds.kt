package voice.core.bookorbit

import voice.core.data.BookId
import voice.core.data.ChapterId

private val uuid =
  Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    RegexOption.IGNORE_CASE,
  )
private val hash = Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE)

internal object BookOrbitIds {
  fun bookId(
    instanceId: String,
    userId: Long,
    bookId: Long,
  ): BookId {
    require(uuid.matches(instanceId) && userId > 0 && bookId > 0)
    return BookId("bo://${instanceId.lowercase()}/$userId/book/$bookId")
  }

  fun chapterId(
    instanceId: String,
    userId: Long,
    bookId: Long,
    sequence: Int,
    assetId: String,
    etag: String,
  ): ChapterId {
    require(
      sequence >= 0 &&
        assetId.startsWith("aud_") &&
        uuid.matches(assetId.removePrefix("aud_")) &&
        hash.matches(etag),
    )
    return ChapterId("${bookId(instanceId, userId, bookId).value}/asset/$sequence/$assetId/$etag")
  }
}

internal data class BookOrbitRef(
  val instanceId: String,
  val userId: Long,
  val bookId: Long,
)

internal data class BookOrbitTrackRef(
  val book: BookOrbitRef,
  val sequence: Int,
  val assetId: String,
  val etag: String,
)

internal val BookId.bookRef: BookOrbitRef?
  get() = bookRef(value)
internal val ChapterId.trackRef: BookOrbitTrackRef?
  get() = trackRef(value)

internal fun bookRef(value: String): BookOrbitRef? {
  val parts = value.removePrefix("bo://").split('/')
  if (
    !value.startsWith("bo://") || parts.size != 4 || !uuid.matches(parts[0]) || parts[2] != "book"
  )
    return null
  val user = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
  val book = parts[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
  return BookOrbitRef(parts[0].lowercase(), user, book)
}

internal fun trackRef(value: String): BookOrbitTrackRef? {
  val parts = value.split('/')
  if (parts.size != 10 || parts[6] != "asset") return null
  val book = bookRef(parts.take(6).joinToString("/")) ?: return null
  val sequence = parts[7].toIntOrNull()?.takeIf { it >= 0 } ?: return null
  val asset =
    parts[8].takeIf { it.startsWith("aud_") && uuid.matches(it.removePrefix("aud_")) }
      ?: return null
  val etag = parts[9].takeIf(hash::matches) ?: return null
  return BookOrbitTrackRef(book, sequence, asset, etag)
}
