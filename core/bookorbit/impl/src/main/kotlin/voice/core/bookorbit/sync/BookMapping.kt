package voice.core.bookorbit.sync

import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import voice.core.bookorbit.BookOrbitIds
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.api.AUDIO_FORMATS
import voice.core.bookorbit.api.AudiobookManifest
import voice.core.bookorbit.api.BookCard
import voice.core.bookorbit.trackRef
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookMetadata
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData

internal fun AudiobookManifest.validate() {
  require(schema == "bookorbit.audiobook-manifest" && schemaVersion == 2)
  require(Regex("[0-9a-f]{64}").matches(revision))
  require(book.id > 0 && assets.isNotEmpty() && totalDurationMs > 0)
  require(assets.map { it.assetId }.distinct().size == assets.size)
  require(assets.map { it.fileId }.distinct().size == assets.size)
  val ordered = assets.sortedBy { it.sequence }
  ordered.forEachIndexed { index, asset ->
    require(
      asset.sequence == index && asset.fileId > 0 && asset.format.lowercase() in AUDIO_FORMATS,
    )
    require(asset.durationMs != null && asset.durationMs in 1..315_576_000_000L)
    require(asset.sizeBytes == null || asset.sizeBytes >= 0)
    val _ =
      BookOrbitIds.chapterId(
        "00000000-0000-4000-8000-000000000000",
        1,
        book.id,
        asset.sequence,
        asset.assetId,
        asset.etag,
      )
  }
  val duration = ordered.sumOf { it.durationMs!! }
  require(kotlin.math.abs(duration - totalDurationMs) <= ordered.size * 1000L)
  require(chapters.map { it.id }.distinct().size == chapters.size)
  require(chapters.map { it.sequence }.distinct().size == chapters.size)
  chapters.forEach { chapter ->
    val asset =
      ordered.firstOrNull { it.assetId == chapter.assetId } ?: error("Chapter has no audio asset")
    val before = ordered.take(asset.sequence).sumOf { it.durationMs!! }
    require(
      chapter.startMs >= 0 && chapter.endMs >= chapter.startMs && chapter.endMs <= duration + 1000,
    )
    require(chapter.assetOffsetMs >= 0 && chapter.assetOffsetMs <= asset.durationMs!!)
    require(chapter.assetOffsetMs == chapter.startMs - before)
  }
}

internal fun AudiobookManifest.chapters(account: Account): List<Chapter> {
  validate()
  var start = 0L
  val marks = chapters.sortedBy { it.startMs }
  return assets
    .sortedBy { it.sequence }
    .map { asset ->
      val duration = checkNotNull(asset.durationMs)
      val end = start + duration
      val running = marks.lastOrNull { it.startMs <= start }
      val inside = marks.filter { it.startMs > start && it.startMs < end }
      val data = buildList {
        if (running != null) add(MarkData(0, running.title))
        inside.forEach { add(MarkData(it.startMs - start, it.title)) }
      }
      start = end
      Chapter(
        id =
          BookOrbitIds.chapterId(
            account.instanceId,
            account.userId,
            book.id,
            asset.sequence,
            asset.assetId,
            asset.etag,
          ),
        name =
          data.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Track ${asset.sequence + 1}",
        duration = duration,
        fileLastModified = Instant.EPOCH,
        fileSize = asset.sizeBytes ?: 0,
        markData = data,
      )
    }
}

internal fun AudiobookManifest.toBookContent(
  account: Account,
  card: BookCard,
  chapters: List<Chapter>,
  existing: BookContent?,
  previousChapters: List<Chapter> = emptyList(),
): BookContent {
  require(chapters.isNotEmpty())
  val sameAsset =
    existing?.currentChapter?.trackRef?.assetId?.let { asset ->
      chapters.firstOrNull { it.id.trackRef?.assetId == asset }
    }
  val position =
    when {
      existing == null -> BookPosition(chapters.first().id, 0)
      sameAsset != null ->
        BookPosition(sameAsset.id, existing.positionInChapter.coerceAtMost(sameAsset.duration))
      else ->
        chapters.positionAt(
          previousChapters.positionInBook(existing.currentChapter, existing.positionInChapter) ?: 0,
        )
    }
  return BookContent(
    id = BookOrbitIds.bookId(account.instanceId, account.userId, book.id),
    playbackSpeed = existing?.playbackSpeed ?: 1F,
    skipSilence = existing?.skipSilence ?: false,
    isActive = true,
    lastPlayedAt = existing?.lastPlayedAt ?: Instant.EPOCH,
    addedAt = existing?.addedAt ?: instant(card.addedAt),
    chapters = chapters.map { it.id },
    currentChapter = position.chapterId,
    positionInChapter = position.positionInChapter,
    gain = existing?.gain ?: 0F,
    metadata =
      BookMetadata(
        name =
          book.title.takeIf { it.isNotBlank() } ?: card.title ?: chapters.first().name.orEmpty(),
        author = book.authors.joinToString(", ").takeIf { it.isNotBlank() },
        album = null,
        title = null,
        genre = card.genres.joinToString(", ").takeIf { it.isNotBlank() },
        narrator = book.narrators.joinToString(", ").takeIf { it.isNotBlank() },
        series = card.seriesName,
        part = (card.seriesIndex as? JsonPrimitive)?.content,
        cover = existing?.metadata?.cover?.takeIf { card.hasCover },
        fileLastModified = null,
      ),
  )
}

internal data class BookPosition(
  val chapterId: ChapterId,
  val positionInChapter: Long,
)

internal fun Book.positionAt(positionMs: Long): BookPosition = chapters.positionAt(positionMs)

internal fun List<Chapter>.positionAt(positionMs: Long): BookPosition {
  require(isNotEmpty())
  var remaining = positionMs.coerceIn(0, sumOf { it.duration })
  forEachIndexed { index, chapter ->
    if (remaining < chapter.duration || index == lastIndex)
      return BookPosition(chapter.id, remaining.coerceAtMost(chapter.duration))
    remaining -= chapter.duration
  }
  error("Missing audiobook asset")
}

internal fun List<Chapter>.positionInBook(
  chapterId: ChapterId,
  positionInChapter: Long,
): Long? {
  if (none { it.id == chapterId }) return null
  return takeWhile { it.id != chapterId }.sumOf { it.duration } + positionInChapter
}

internal val Book.isFinished: Boolean
  get() = position > 0 && duration - position < 10_000

internal fun instant(value: String): Instant = runCatching {
  Instant.parse(value)
}
  .getOrDefault(Instant.EPOCH)
