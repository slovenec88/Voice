package voice.core.bookorbit

import java.io.File
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface BookOrbit {
  val connection: Flow<BookOrbitConnection?>

  suspend fun isConnected(): Boolean

  suspend fun savedCloudflareAccess(serverUrl: String): CloudflareAccess?

  suspend fun findServer(
    address: String,
    cloudflareAccess: CloudflareAccess? = null,
  ): FindServerResult

  suspend fun login(server: BookOrbitServer, username: String, password: String): LoginResult

  suspend fun preview(login: PendingLogin): LibrariesPreviewResult

  suspend fun libraries(): LibrariesPreview?

  suspend fun connect(login: PendingLogin, libraryIds: List<String>): Boolean

  suspend fun renewLogin(login: PendingLogin): Boolean

  suspend fun setLibraries(libraryIds: List<String>)

  suspend fun signOut()
}

data class BookOrbitConnection(
  val serverUrl: String,
  val username: String,
  val libraryIds: List<String>,
  val needsLogin: Boolean,
) {
  val serverName: String
    get() {
      val url = serverUrl.toHttpUrlOrNull() ?: return serverUrl
      val port = if (url.port == if (url.isHttps) 443 else 80) "" else ":${url.port}"
      return url.host + port + url.encodedPath.trimEnd('/')
    }
}

interface BookOrbitServer {
  val version: String
  val passwordLogin: Boolean
}

sealed interface FindServerResult {
  data class Found(val server: BookOrbitServer) : FindServerResult

  data object NotFound : FindServerResult

  data object CertificateNotTrusted : FindServerResult

  data object CloudflareAccessFailed : FindServerResult

  data object NotSetUp : FindServerResult

  data class TooOld(val version: String) : FindServerResult
}

interface PendingLogin {
  val serverName: String
}

sealed interface LoginResult {
  data class Success(val login: PendingLogin) : LoginResult

  data object WrongCredentials : LoginResult

  data class Unreachable(val reason: String) : LoginResult

  data object Failed : LoginResult
}

data class BookOrbitLibrary(
  val id: String,
  val name: String,
  val bookCount: Int,
)

data class LibrariesPreview(
  val libraries: List<BookOrbitLibrary>,
  val covers: List<File>,
)

sealed interface LibrariesPreviewResult {
  data class Success(val preview: LibrariesPreview) : LibrariesPreviewResult

  data class Failed(val code: String) : LibrariesPreviewResult
}
