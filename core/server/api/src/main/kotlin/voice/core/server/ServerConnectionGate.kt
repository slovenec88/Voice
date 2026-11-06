package voice.core.server

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@SingleIn(AppScope::class)
class ServerConnectionGate {
  private val mutex = Mutex()
  private val selected = MutableStateFlow<String?>(null)
  val provider: StateFlow<String?> = selected

  @Inject
  constructor(context: Context) : this() {
    selected.value =
      listOf("abs" to "audiobookshelf", "bo" to "bookorbit")
        .firstOrNull { (_, directory) ->
          runCatching {
              val file = File(context.filesDir, "$directory/account.json")
              file.isFile && JSONObject(file.readText()).has("serverUrl")
            }
            .getOrDefault(false)
        }
        ?.first
  }

  constructor()

  suspend fun claim(scheme: String): Boolean = mutex.withLock {
    require(scheme == "abs" || scheme == "bo")
    if (selected.value != null && selected.value != scheme) return@withLock false
    selected.value = scheme
    true
  }

  suspend fun release(scheme: String) = mutex.withLock {
    if (selected.value == scheme) selected.value = null
  }
}
