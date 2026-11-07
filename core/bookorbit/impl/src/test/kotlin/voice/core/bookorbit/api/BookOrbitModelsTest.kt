package voice.core.bookorbit.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import voice.core.bookorbit.FIRST_ASSET_ID
import voice.core.bookorbit.SECOND_ASSET_ID
import voice.core.bookorbit.bookOrbitJson
import voice.core.bookorbit.fixture

class BookOrbitModelsTest {

  @Test
  fun `current manifest decodes ordered physical tracks and chapter offsets`() {
    val manifest = bookOrbitJson.decodeFromString<AudiobookManifest>(fixture("manifest"))

    assertEquals(2, manifest.schemaVersion)
    assertEquals("bookorbit.audiobook-manifest", manifest.schema)
    assertEquals(listOf(FIRST_ASSET_ID, SECOND_ASSET_ID), manifest.assets.map { it.assetId })
    assertEquals(120_000L, manifest.totalDurationMs)
    assertNull(manifest.assets[1].sizeBytes)
    assertEquals(90_000L, manifest.chapters[1].startMs)
    assertEquals(30_000L, manifest.chapters[1].assetOffsetMs)
  }

  @Test
  fun `native login credentials are top level and user identifiers are numeric`() {
    val response = bookOrbitJson.decodeFromString<LoginResponse>(fixture("native-login"))

    assertEquals("access-one", response.accessToken)
    assertEquals("refresh-one", response.refreshToken)
    assertEquals(7L, response.user.id)
    assertEquals("listener", response.user.username)
    assertEquals(81L, response.sessionId)
  }

  @Test
  fun `bookmark UUIDs distinguish marks at the same position and preserve notes`() {
    val bookmarks = bookOrbitJson.decodeFromString<List<BookOrbitBookmark>>(fixture("bookmarks"))

    assertEquals(2, bookmarks.size)
    assertEquals(bookmarks[0].positionMs, bookmarks[1].positionMs)
    assertNotEquals(bookmarks[0].id, bookmarks[1].id)
    assertTrue(bookmarks[0].note!!.contains("server-only note"))
  }

  @Test
  fun `renaming a bookmark sends only the title`() {
    val encoded = bookOrbitJson.encodeToString(BookmarkUpdate("New name"))

    assertEquals(setOf("title"), Json.parseToJsonElement(encoded).jsonObject.keys)
  }

  @Test
  fun `an ebook with a separate physical audio format is an audiobook`() {
    assertTrue(BookCard(42, files = listOf(BookFile(1, "epub"), BookFile(2, "m4b"))).hasAudio)
    assertTrue(BookCard(42, files = listOf(BookFile(1, "MP3", role = "primary"))).hasAudio)
  }

  @Test
  fun `ebook overlays and audio attachments do not become audiobook tracks`() {
    assertFalse(BookCard(42, files = listOf(BookFile(1, "epub"))).hasAudio)
    assertFalse(BookCard(42, files = listOf(BookFile(1, "mp3", role = "attachment"))).hasAudio)
    assertFalse(BookCard(42, files = listOf(BookFile(1, null))).hasAudio)
    assertFalse(BookCard(42).hasAudio)
  }
}
