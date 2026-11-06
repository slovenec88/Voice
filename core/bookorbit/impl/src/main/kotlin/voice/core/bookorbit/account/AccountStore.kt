package voice.core.bookorbit.account

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import voice.core.bookorbit.BookOrbitRef
import voice.core.bookorbit.CloudflareAccess
import voice.core.bookorbit.api.AudiobookManifest
import voice.core.bookorbit.api.BookCard
import voice.core.bookorbit.api.BookmarkRequest
import voice.core.bookorbit.api.PlaybackUpdate
import voice.core.bookorbit.api.SessionRequest
import voice.core.bookorbit.bookOrbitJson

@Serializable
internal data class Account(
  val serverUrl: String,
  val instanceId: String,
  val userId: Long,
  val username: String,
  val accessToken: String,
  val refreshToken: String,
  val libraryIds: List<Long>,
  val serverVersion: String = "",
  val needsLogin: Boolean = false,
  val cloudflareAccess: CloudflareAccess? = null,
) {
  fun owns(ref: BookOrbitRef?): Boolean =
    ref != null && ref.instanceId == instanceId && ref.userId == userId

  fun sameOwner(other: Account?): Boolean =
    other?.instanceId == instanceId && other.userId == userId && other.serverUrl == serverUrl
}

@Serializable
internal data class Preferences(
  val downloadOverMobileData: Boolean = false,
  val askedForNotifications: Boolean = false,
)

@Serializable
internal data class PendingProgress(
  val update: PlaybackUpdate,
  val absolutePositionMs: Long,
  val finished: Boolean,
  val status: String? = null,
  val positionAccepted: Boolean = false,
)

@Serializable
internal data class SyncedProgress(
  val positionMs: Long = 0,
  val finished: Boolean = false,
  val capturedAt: String = "1970-01-01T00:00:00Z",
  val revision: Long = 0,
  val pending: PendingProgress? = null,
  val queued: PendingProgress? = null,
  val ownStatusUpdatedAt: String? = null,
)

@Serializable
internal data class SyncedBookmark(
  val title: String,
  val positionMs: Long,
  val replacement: BookmarkRequest? = null,
)

@Serializable
internal data class CachedBook(
  val manifest: AudiobookManifest,
  val card: BookCard,
)

@Serializable
internal data class PendingSession(
  val fileId: Long,
  val request: SessionRequest,
)

@SingleIn(AppScope::class)
internal class AccountStore(store: DataStore<Account?>) : DataStore<Account?> by store {

  @Inject
  constructor(
    context: Context,
  ) : this(
    jsonStore(
      context = context,
      fileName = "account",
      serializer = Account.serializer().nullable,
      defaultValue = null,
    ),
  )
}

@SingleIn(AppScope::class)
internal class PreferencesStore(store: DataStore<Preferences>) : DataStore<Preferences> by store {

  @Inject
  constructor(
    context: Context,
  ) : this(
    jsonStore(
      context = context,
      fileName = "preferences",
      serializer = Preferences.serializer(),
      defaultValue = Preferences(),
    ),
  )
}

@SingleIn(AppScope::class)
internal class SyncedProgressStore(store: DataStore<Map<String, SyncedProgress>>) :
  DataStore<Map<String, SyncedProgress>> by store {

  @Inject
  constructor(
    context: Context,
  ) : this(
    jsonStore(
      context = context,
      fileName = "syncedProgress",
      serializer = MapSerializer(String.serializer(), SyncedProgress.serializer()),
      defaultValue = emptyMap(),
    ),
  )
}

@SingleIn(AppScope::class)
internal class SyncedBookmarksStore(store: DataStore<Map<String, Map<String, SyncedBookmark>>>) :
  DataStore<Map<String, Map<String, SyncedBookmark>>> by store {
  @Inject
  constructor(
    context: Context,
  ) : this(
    jsonStore(
      context,
      "syncedBookmarks",
      MapSerializer(
        String.serializer(),
        MapSerializer(String.serializer(), SyncedBookmark.serializer()),
      ),
      emptyMap(),
    ),
  )
}

@SingleIn(AppScope::class)
internal class CachedBooksStore(store: DataStore<Map<String, CachedBook>>) :
  DataStore<Map<String, CachedBook>> by store {
  @Inject
  constructor(
    context: Context,
  ) : this(
    jsonStore(
      context,
      "books",
      MapSerializer(String.serializer(), CachedBook.serializer()),
      emptyMap(),
    ),
  )
}

@SingleIn(AppScope::class)
internal class PendingSessionsStore(store: DataStore<Map<String, PendingSession>>) :
  DataStore<Map<String, PendingSession>> by store {
  @Inject
  constructor(
    context: Context,
  ) : this(
    jsonStore(
      context,
      "pendingSessions",
      MapSerializer(String.serializer(), PendingSession.serializer()),
      emptyMap(),
    ),
  )
}

private fun <T> jsonStore(
  context: Context,
  fileName: String,
  serializer: KSerializer<T>,
  defaultValue: T,
): DataStore<T> {
  return DataStoreFactory.create(
    serializer =
      object : Serializer<T> {
        override val defaultValue: T = defaultValue

        override suspend fun readFrom(input: InputStream): T {
          return try {
            bookOrbitJson.decodeFromString(serializer, input.readBytes().decodeToString())
          } catch (e: SerializationException) {
            throw CorruptionException("Could not read $fileName", e)
          }
        }

        override suspend fun writeTo(
          t: T,
          output: OutputStream,
        ) {
          output.write(bookOrbitJson.encodeToString(serializer, t).encodeToByteArray())
        }
      },
  ) {
    File(context.filesDir, "bookorbit/$fileName.json")
  }
}
