package voice.core.bookorbit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.api.AudiobookManifest
import voice.core.bookorbit.api.BookCard
import voice.core.bookorbit.sync.chapters
import voice.core.bookorbit.sync.positionAt
import voice.core.bookorbit.sync.toBookContent
import voice.core.data.Book
import voice.core.data.CustomMetadata
import voice.core.server.ServerConnectionGate

internal const val INSTANCE_ID = "00f8c089-7bb8-4fb2-9faf-fad5fb86a03a"
internal const val OTHER_INSTANCE_ID = "451f3757-251a-4b8b-920a-e7c984d26bd1"
internal const val FIRST_ASSET_ID = "aud_aaaaaaaa-1111-4111-8111-111111111111"
internal const val SECOND_ASSET_ID = "aud_bbbbbbbb-2222-4222-8222-222222222222"
internal const val FIRST_ETAG = "1111111111111111111111111111111111111111111111111111111111111111"
internal const val SECOND_ETAG = "2222222222222222222222222222222222222222222222222222222222222222"

internal fun testConnectionGate(): ServerConnectionGate =
  ServerConnectionGate().also { gate ->
    runBlocking { check(gate.claim("bo")) }
  }

internal fun fixture(name: String): String =
  checkNotNull(
      object {}.javaClass.getResourceAsStream("/bookorbit/$name.json"),
    )
    .bufferedReader()
    .use { it.readText() }

internal fun testAccount(serverUrl: String = "https://example.org/books/") =
  Account(
    serverUrl = serverUrl,
    instanceId = INSTANCE_ID,
    userId = 7,
    username = "listener",
    accessToken = "access-one",
    refreshToken = "refresh-one",
    libraryIds = listOf(3),
  )

internal fun testManifest(): AudiobookManifest = bookOrbitJson.decodeFromString(fixture("manifest"))

internal fun testCard() =
  BookCard(id = 42, libraryId = 3, title = "A Story in Two Files", addedAt = "2026-10-01T10:00:00Z")

internal fun testBook(
  positionMs: Long = 0,
  account: Account = testAccount(),
  manifest: AudiobookManifest = testManifest(),
): Book {
  val chapters = manifest.chapters(account)
  val position = chapters.positionAt(positionMs)
  val content =
    manifest
      .toBookContent(account, testCard(), chapters, existing = null)
      .copy(
        currentChapter = position.chapterId,
        positionInChapter = position.positionInChapter,
      )
  return Book(content, chapters, CustomMetadata(content.id))
}
