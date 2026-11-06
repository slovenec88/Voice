package voice.core.bookorbit.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import voice.core.bookorbit.FIRST_ASSET_ID
import voice.core.bookorbit.SECOND_ETAG
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testBook
import voice.core.bookorbit.testCard
import voice.core.bookorbit.testManifest
import voice.core.bookorbit.trackRef
import voice.core.data.ChapterMark

class BookMappingTest {

  private val account = testAccount()

  @Test
  fun `chapters spanning files continue with a zero offset in the next file`() {
    val chapters = testManifest().chapters(account)

    assertEquals(listOf(60_000L, 60_000L), chapters.map { it.duration })
    assertEquals(listOf(ChapterMark("The Long Chapter", 0, 59_999)), chapters[0].chapterMarks)
    assertEquals(
      listOf(
        ChapterMark("The Long Chapter", 0, 29_999),
        ChapterMark("The Last Chapter", 30_000, 59_999),
      ),
      chapters[1].chapterMarks,
    )
  }

  @Test
  fun `server sequence determines track order regardless of response ordering`() {
    val manifest = testManifest()

    assertEquals(
      manifest.chapters(account),
      manifest.copy(assets = manifest.assets.reversed()).chapters(account),
    )
  }

  @Test
  fun `books with no embedded chapters still have navigable files`() {
    val chapters = testManifest().copy(chapters = emptyList()).chapters(account)

    assertEquals(listOf("Track 1", "Track 2"), chapters.map { it.name })
    assertEquals(1, chapters[1].chapterMarks.size)
    assertEquals(0L, chapters[1].chapterMarks.single().startMs)
  }

  @Test
  fun `new metadata preserves local listening preferences and capture time`() {
    val manifest = testManifest()
    val chapters = manifest.chapters(account)
    val existing =
      testBook(75_000)
        .content
        .copy(
          playbackSpeed = 1.75F,
          skipSilence = true,
          gain = 2F,
          lastPlayedAt = Instant.parse("2026-10-30T10:00:00Z"),
        )
    val card =
      testCard()
        .copy(
          seriesName = "Series",
          seriesIndex = JsonPrimitive(2.5),
          genres = listOf("Fantasy", "Adventure"),
        )
    val updated = manifest.toBookContent(account, card, chapters, existing)

    assertEquals("A. Author, B. Author", updated.metadata.author)
    assertEquals("The Narrator", updated.metadata.narrator)
    assertEquals("Series", updated.metadata.series)
    assertEquals("2.5", updated.metadata.part)
    assertEquals("Fantasy, Adventure", updated.metadata.genre)
    assertEquals(existing.currentChapter, updated.currentChapter)
    assertEquals(15_000L, updated.positionInChapter)
    assertEquals(existing.playbackSpeed, updated.playbackSpeed)
    assertEquals(existing.skipSilence, updated.skipSilence)
    assertEquals(existing.gain, updated.gain)
    assertEquals(existing.lastPlayedAt, updated.lastPlayedAt)
    assertEquals(existing.addedAt, updated.addedAt)
  }

  @Test
  fun `content changes invalidate audio keys while keeping the position in the same asset`() {
    val old = testManifest()
    val before = testBook(20_000)
    val changed =
      old.copy(
        assets =
          old.assets.map { if (it.assetId == FIRST_ASSET_ID) it.copy(etag = SECOND_ETAG) else it },
      )
    val chapters = changed.chapters(account)
    val updated =
      changed.toBookContent(account, testCard(), chapters, before.content, before.chapters)

    assertNotEquals(before.content.currentChapter, updated.currentChapter)
    assertEquals(
      before.content.currentChapter.trackRef?.assetId,
      updated.currentChapter.trackRef?.assetId,
    )
    assertEquals(20_000L, updated.positionInChapter)
  }

  @Test
  fun `chapter edits keep downloaded audio identities`() {
    val manifest = testManifest()
    val after =
      manifest.copy(
        revision = "b".repeat(64),
        chapters = manifest.chapters.map { it.copy(title = "Renamed") },
      )

    assertEquals(manifest.chapters(account).map { it.id }, after.chapters(account).map { it.id })
  }

  @Test
  fun `reordering tracks keeps the current asset and relative position`() {
    val manifest = testManifest().copy(chapters = emptyList())
    val before = testBook(75_000, manifest = manifest)
    val after =
      manifest.copy(
        assets =
          manifest.assets.reversed().mapIndexed { index, asset -> asset.copy(sequence = index) },
      )
    val chapters = after.chapters(account)
    val updated =
      after.toBookContent(account, testCard(), chapters, before.content, before.chapters)

    assertEquals(
      before.currentChapter.id.trackRef?.assetId,
      updated.currentChapter.trackRef?.assetId,
    )
    assertEquals(chapters.first().id, updated.currentChapter)
    assertEquals(15_000L, updated.positionInChapter)
  }

  @Test
  fun `replacing all files remaps to the same absolute listening position`() {
    val manifest = testManifest()
    val before = testBook(75_000)
    val after =
      manifest.copy(
        assets =
          listOf(
            manifest.assets[0].copy(
              assetId = "aud_cccccccc-3333-4333-8333-333333333333",
              durationMs = 120_000,
            ),
          ),
        chapters = emptyList(),
      )
    val chapters = after.chapters(account)
    val updated =
      after.toBookContent(account, testCard(), chapters, before.content, before.chapters)

    assertEquals(chapters.single().id, updated.currentChapter)
    assertEquals(75_000L, updated.positionInChapter)
  }

  @Test
  fun `boundary and out of range positions map to the appropriate asset`() {
    val chapters = testManifest().chapters(account)

    assertEquals(BookPosition(chapters[0].id, 0), chapters.positionAt(-1))
    assertEquals(BookPosition(chapters[1].id, 0), chapters.positionAt(60_000))
    assertEquals(BookPosition(chapters[1].id, 60_000), chapters.positionAt(120_001))
    assertNull(chapters.positionInBook(voice.core.data.ChapterId("missing"), 1))
  }

  @Test
  fun `invalid manifests never become a playable one millisecond book`() {
    val manifest = testManifest()
    listOf(
        manifest.copy(schemaVersion = 1),
        manifest.copy(schema = "other"),
        manifest.copy(assets = emptyList()),
        manifest.copy(assets = listOf(manifest.assets[0], manifest.assets[0])),
        manifest.copy(assets = manifest.assets.map { it.copy(sequence = 0) }),
        manifest.copy(assets = manifest.assets.map { it.copy(durationMs = null) }),
        manifest.copy(assets = manifest.assets.map { it.copy(durationMs = -1) }),
        manifest.copy(assets = manifest.assets.map { it.copy(format = "epub") }),
        manifest.copy(assets = manifest.assets.map { it.copy(sizeBytes = -1) }),
        manifest.copy(chapters = listOf(manifest.chapters[0], manifest.chapters[0])),
      )
      .forEach { invalid ->
        assertFailsWith<IllegalArgumentException> { invalid.chapters(account) }
      }
  }
}
