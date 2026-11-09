package voice.core.bookorbit

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.LoginRequest
import voice.core.bookorbit.api.RefreshRequest
import voice.core.bookorbit.api.ServerIdentity
import voice.core.bookorbit.api.hasAudio
import voice.core.bookorbit.download.BookOrbitDownloads
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.login.isLocalHost
import voice.core.bookorbit.login.serverUrlCandidates
import voice.core.bookorbit.sync.BookOrbitSync
import voice.core.bookorbit.sync.bookQuery
import voice.core.data.isBookOrbit
import voice.core.data.repo.BookContentRepo
import voice.core.data.store.CurrentBookStore
import voice.core.playback.PlayerController
import voice.core.server.ServerConnectionGate

private class VerifiedServer(
  val url: String,
  val instanceId: String,
  override val version: String,
  override val passwordLogin: Boolean,
  val cloudflareAccess: CloudflareAccess?,
) : BookOrbitServer

internal class BookOrbitLogin(val account: Account) : PendingLogin {
  override val serverName: String
    get() = account.toConnection().serverName
}

internal val PendingLogin.account: Account
  get() = (this as BookOrbitLogin).account

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class BookOrbitImpl
internal constructor(
  private val accountStore: AccountStore,
  private val http: BookOrbitHttp,
  private val sync: BookOrbitSync,
  private val downloads: BookOrbitDownloads,
  private val contentRepo: BookContentRepo,
  private val currentBookStore: CurrentBookStore,
  private val playerController: PlayerController,
  private val context: Context,
  private val connectionGate: ServerConnectionGate,
) : BookOrbit {
  init {
    runBlocking {
      if (accountStore.data.first() != null) {
        val _ = connectionGate.claim("bo")
      }
    }
  }

  override val connection: Flow<BookOrbitConnection?> =
    combine(accountStore.data, connectionGate.provider) { account, selected ->
        account?.takeIf { selected == "bo" }?.toConnection()
      }
      .distinctUntilChanged()

  override suspend fun isConnected(): Boolean = connection.first() != null

  override suspend fun savedCloudflareAccess(serverUrl: String): CloudflareAccess? =
    accountStore.data.first()?.takeIf { it.serverUrl == serverUrl }?.cloudflareAccess

  override suspend fun findServer(
    address: String,
    cloudflareAccess: CloudflareAccess?,
  ): FindServerResult =
    withContext(Dispatchers.IO) {
      val candidates =
        serverUrlCandidates(address).let { urls ->
          if (cloudflareAccess == null) urls else urls.filter { it.startsWith("https://") }.take(1)
        }
      if (cloudflareAccess != null && (!cloudflareAccess.isValid() || candidates.isEmpty())) {
        return@withContext FindServerResult.CloudflareAccessFailed
      }
      var certificate = false
      var accessDenied = false
      val unreachable = mutableSetOf<String>()
      val probeClient =
        http.client.newBuilder().followRedirects(true).followSslRedirects(true).build()
      for (candidate in candidates) {
        val url = candidate.toHttpUrlOrNull() ?: continue
        val origin = "${url.scheme}://${url.host}:${url.port}"
        if (origin in unreachable || (certificate && !url.isHttps && !isLocalHost(url.host)))
          continue
        try {
          val client =
            if (cloudflareAccess == null) probeClient
            else http.clientFor(candidate, cloudflareAccess)
          val response =
            client
              .newCall(Request.Builder().url("${candidate}api/v1/server-identity").build())
              .execute()
          response.use {
            if (!it.isSuccessful) {
              accessDenied =
                accessDenied || (cloudflareAccess != null && it.code.mayBeAccessDenied())
              return@use
            }
            val final = it.request.url
            if (
              !final.isHttps && !isLocalHost(final.host) && (url.isHttps || url.host != final.host)
            )
              return@use
            val identity = bookOrbitJson.decodeFromString<ServerIdentity>(it.body.string())
            if (identity.product != "bookorbit") return@use
            if (identity.protocolVersion != 1) return@withContext FindServerResult.TooOld("")
            try {
              val _ = BookOrbitIds.bookId(identity.instanceId, 1, 1)
            } catch (_: IllegalArgumentException) {
              return@use
            }
            val root =
              final
                .newBuilder()
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .removeSuffix("api/v1/server-identity")
            if (!root.endsWith('/')) return@use
            val api = http.api(root, cloudflareAccess)
            if (api.setupStatus().needsSetup) return@withContext FindServerResult.NotSetUp
            val options = api.loginOptions()
            val version =
              try {
                api.appInfo().version
              } catch (_: HttpException) {
                ""
              }
            return@withContext FindServerResult.Found(
              VerifiedServer(
                root,
                identity.instanceId.lowercase(),
                version,
                options.passwordLoginEnabled,
                cloudflareAccess,
              ),
            )
          }
        } catch (e: SSLException) {
          if (
            e is SSLPeerUnverifiedException ||
              generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }
          ) {
            certificate = true
          }
          unreachable += origin
        } catch (_: IOException) {
          unreachable += origin
        } catch (e: HttpException) {
          accessDenied = accessDenied || (cloudflareAccess != null && e.code().mayBeAccessDenied())
        } catch (_: SerializationException) {}
      }
      when {
        certificate -> FindServerResult.CertificateNotTrusted
        accessDenied -> FindServerResult.CloudflareAccessFailed
        else -> FindServerResult.NotFound
      }
    }

  override suspend fun login(
    server: BookOrbitServer,
    username: String,
    password: String,
  ): LoginResult {
    val verified = server as? VerifiedServer ?: return LoginResult.Failed
    if (!verified.passwordLogin) return LoginResult.Failed
    return try {
      val response =
        http.login(verified.url, verified.cloudflareAccess, LoginRequest(username.trim(), password))
      if (
        response.accessToken.isBlank() ||
          response.refreshToken.isBlank() ||
          response.user.id <= 0 ||
          response.user.isDefaultPassword
      ) {
        return LoginResult.Failed
      }
      LoginResult.Success(
        BookOrbitLogin(
          Account(
            verified.url,
            verified.instanceId,
            response.user.id,
            response.user.username,
            response.accessToken,
            response.refreshToken,
            emptyList(),
            verified.version,
            cloudflareAccess = verified.cloudflareAccess,
          ),
        ),
      )
    } catch (e: HttpException) {
      if (e.code() == 401 || e.code() == 403) LoginResult.WrongCredentials else LoginResult.Failed
    } catch (failure: IOException) {
      LoginResult.Unreachable(failure.javaClass.simpleName)
    } catch (_: SerializationException) {
      LoginResult.Failed
    }
  }

  override suspend fun preview(login: PendingLogin): LibrariesPreviewResult =
    preview(
      login.account,
      http.withToken(login.account),
      http.clientFor(login.account.serverUrl, login.account.cloudflareAccess),
    )

  override suspend fun libraries(): LibrariesPreview? {
    val account = accountStore.data.first() ?: return null
    return (preview(account, http.authenticatedApi(account), http.authenticatedClient)
        as? LibrariesPreviewResult.Success)
      ?.preview
  }

  private suspend fun preview(
    account: Account,
    api: BookOrbitApi,
    coverClient: OkHttpClient,
  ): LibrariesPreviewResult =
    withContext(Dispatchers.IO) {
      var stage = "LIBRARIES"
      try {
        val libraries = api.libraries()
        stage = "BOOKS"
        val pages = libraries.associate { it.id to api.books(it.id, bookQuery(0, 8)) }
        val covers =
          pages.values
            .flatMap { it.items }
            .filter { it.hasCover && it.hasAudio }
            .take(5)
            .mapNotNull { item ->
              val folder = File(context.cacheDir, "bookorbitPreview").apply { mkdirs() }
              val file = File(folder, "${account.instanceId}-${account.userId}-${item.id}.jpg")
              val request =
                Request.Builder()
                  .url(
                    "${account.serverUrl}api/v1/books/${item.id}/cover?medium=audio",
                  )
                  .header("Authorization", "Bearer ${account.accessToken}")
                  .tag(Account::class.java, account)
                  .build()
              try {
                coverClient.newCall(request).execute().use { response ->
                  if (
                    !response.isSuccessful ||
                      response.header("Content-Type")?.startsWith("image/") != true
                  )
                    return@mapNotNull null
                  file.outputStream().use { response.body.byteStream().copyTo(it) }
                  file
                }
              } catch (_: IOException) {
                null
              }
            }
        LibrariesPreviewResult.Success(
          LibrariesPreview(
            libraries.map { BookOrbitLibrary(it.id.toString(), it.name, pages[it.id]?.total ?: 0) },
            covers,
          ),
        )
      } catch (failure: IOException) {
        LibrariesPreviewResult.Failed("BO-$stage-${failure.javaClass.simpleName}")
      } catch (failure: HttpException) {
        LibrariesPreviewResult.Failed("BO-$stage-HTTP${failure.code()}")
      } catch (_: SerializationException) {
        LibrariesPreviewResult.Failed("BO-$stage-JSON")
      }
    }

  override suspend fun connect(
    login: PendingLogin,
    libraryIds: List<String>,
  ): Boolean {
    val current = accountStore.data.first()
    if (
      current != null &&
        (current.instanceId != login.account.instanceId || current.userId != login.account.userId)
    )
      signOut()
    if (!connectionGate.claim("bo")) return false
    accountStore.updateData {
      login.account.copy(
        libraryIds =
          libraryIds.mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }.distinct(),
      )
    }
    sync.sync(force = true)
    return true
  }

  override suspend fun renewLogin(login: PendingLogin): Boolean {
    val current = accountStore.data.first() ?: return false
    if (current.instanceId != login.account.instanceId || current.userId != login.account.userId)
      return false
    accountStore.updateData {
      if (it?.sameOwner(current) == true) login.account.copy(libraryIds = it.libraryIds) else it
    }
    sync.sync(force = true)
    return true
  }

  override suspend fun setLibraries(libraryIds: List<String>) {
    accountStore.updateData {
      it?.copy(
        libraryIds = libraryIds.mapNotNull(String::toLongOrNull).filter { id -> id > 0 }.distinct(),
      )
    }
    sync.sync(force = true)
  }

  override suspend fun signOut() {
    val account = accountStore.data.first() ?: return
    sync.beforeSignOut()
    accountStore.updateData { null }
    sync.cancel()
    if (currentBookStore.data.first()?.isBookOrbit == true) {
      playerController.pause()
      currentBookStore.updateData { null }
    }
    downloads.removeAll()
    contentRepo
      .all()
      .filter { it.id.isBookOrbit }
      .forEach { contentRepo.put(it.copy(isActive = false)) }
    sync.onSignedOut()
    connectionGate.release("bo")
    try {
      @Suppress("RETURN_VALUE_NOT_USED")
      http
        .api(account.serverUrl, account.cloudflareAccess)
        .logout(RefreshRequest(account.refreshToken))
    } catch (_: IOException) {}
  }
}

private fun Account.toConnection() =
  BookOrbitConnection(serverUrl, username, libraryIds.map(Long::toString), needsLogin)

private fun Int.mayBeAccessDenied(): Boolean = this in 300..399 || this == 401 || this == 403
