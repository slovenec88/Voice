package voice.core.bookorbit.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class ServerIdentity(
  val product: String,
  val protocolVersion: Int,
  val instanceId: String,
)

@Serializable internal data class LoginOptions(val passwordLoginEnabled: Boolean = false)

@Serializable internal data class SetupStatus(val needsSetup: Boolean)

@Serializable internal data class AppInfo(val version: String = "")

@Serializable
internal data class LoginRequest(
  val username: String,
  val password: String,
  val clientKind: String = "native",
  val deviceLabel: String = "Voice",
)

@Serializable internal data class RefreshRequest(val refreshToken: String)

@Serializable
internal data class BoUser(
  val id: Long,
  val username: String,
  val isDefaultPassword: Boolean = false,
)

@Serializable
internal data class LoginResponse(
  val accessToken: String,
  val refreshToken: String,
  val user: BoUser,
  val accessTokenExpiresAt: String = "",
  val refreshTokenExpiresAt: String = "",
  val sessionId: Long = 0,
)

@Serializable
internal data class NativeTokens(
  val accessToken: String,
  val refreshToken: String,
  val accessTokenExpiresAt: String = "",
  val refreshTokenExpiresAt: String = "",
  val sessionId: Long = 0,
)

@Serializable
internal data class BoLibrary(
  val id: Long,
  val name: String,
  val type: String,
)

@Serializable
internal data class BookFile(
  val id: Long,
  val format: String? = null,
  val role: String = "content",
  val sizeBytes: Long? = null,
)

@Serializable
internal data class BookReadStatus(
  val status: String,
  val updatedAt: String,
)

@Serializable internal data class BookStatusDetail(val readStatus: BookReadStatus? = null)

@Serializable
internal data class BookCard(
  val id: Long,
  val libraryId: Long? = null,
  val title: String? = null,
  val authors: List<String> = emptyList(),
  val narrators: List<String> = emptyList(),
  val genres: List<String> = emptyList(),
  val seriesName: String? = null,
  val seriesIndex: JsonElement? = null,
  val files: List<BookFile> = emptyList(),
  val addedAt: String = "1970-01-01T00:00:00Z",
  val updatedAt: String? = null,
  val hasCover: Boolean = false,
  val coverVersion: String = "",
  val readStatus: BookReadStatus? = null,
)

@Serializable
internal data class BookPage(
  val items: List<BookCard> = emptyList(),
  val total: Int = 0,
  val page: Int = 0,
  val size: Int = 200,
)

@Serializable
internal data class ManifestBook(
  val id: Long,
  val title: String,
  val authors: List<String> = emptyList(),
  val narrators: List<String> = emptyList(),
  val hasCover: Boolean = false,
)

@Serializable
internal data class ManifestAsset(
  val assetId: String,
  val fileId: Long,
  val sequence: Int,
  val format: String,
  val durationMs: Long?,
  val sizeBytes: Long? = null,
  val etag: String,
)

@Serializable
internal data class ManifestChapter(
  val id: String,
  val title: String,
  val assetId: String,
  val sequence: Int,
  val startMs: Long,
  val endMs: Long,
  val assetOffsetMs: Long,
)

@Serializable
internal data class AudiobookManifest(
  val schema: String,
  val schemaVersion: Int,
  val revision: String,
  val book: ManifestBook,
  val assets: List<ManifestAsset>,
  val chapters: List<ManifestChapter> = emptyList(),
  val totalDurationMs: Long,
)

@Serializable
internal data class PlaybackState(
  val assetId: String,
  val positionMs: Long,
  val percentage: Double = 0.0,
  val completed: Boolean = false,
  val capturedAt: String,
  val revision: Long,
  val manifestRevision: String,
)

@Serializable
internal data class PlaybackUpdate(
  val assetId: String,
  val positionMs: Long,
  val capturedAt: String,
  val operationId: String,
  val baseRevision: Long,
  val manifestRevision: String,
)

@Serializable internal data class StatusUpdate(val status: String)

@Serializable
internal data class BookOrbitBookmark(
  val id: String,
  val bookId: Long,
  val positionMs: Long,
  val title: String,
  val chapterId: String? = null,
  val note: String? = null,
  val createdAt: String,
  val updatedAt: String,
)

@Serializable
internal data class BookmarkRequest(
  val clientId: String,
  val positionMs: Long,
  val title: String,
  val chapterId: String? = null,
  val note: String? = null,
)

@Serializable
internal data class BookmarkUpdate(
  val title: String,
  val note: String? = null,
)

@Serializable
internal data class SessionRequest(
  val sessionId: String,
  val startedAt: String,
  val endedAt: String,
  val durationSeconds: Long,
  val progressDelta: Double = 0.0,
  val endProgress: Double = 0.0,
  val sessionType: String = "listen",
  val source: String = "android",
)

internal val AUDIO_FORMATS = setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")
internal val BookCard.hasAudio: Boolean
  get() = files.any {
    it.role in setOf("primary", "content") && it.format?.lowercase() in AUDIO_FORMATS
  }
