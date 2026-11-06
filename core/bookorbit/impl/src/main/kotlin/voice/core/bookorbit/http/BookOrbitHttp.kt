package voice.core.bookorbit.http

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Route
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import voice.core.bookorbit.CloudflareAccess
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.PlaybackState
import voice.core.bookorbit.api.RefreshRequest
import voice.core.bookorbit.bookOrbitJson
import voice.core.bookorbit.isValid
import voice.core.common.AppInfoProvider
import voice.core.server.ServerConnectionGate

@SingleIn(AppScope::class)
internal class BookOrbitHttp(
  private val accountStore: AccountStore,
  appInfoProvider: AppInfoProvider,
  private val connectionGate: ServerConnectionGate,
  clientBuilder: OkHttpClient.Builder,
) {
  @Inject
  constructor(
    accountStore: AccountStore,
    appInfoProvider: AppInfoProvider,
    connectionGate: ServerConnectionGate,
  ) : this(
    accountStore,
    appInfoProvider,
    connectionGate,
    OkHttpClient.Builder(),
  )

  val client: OkHttpClient =
    clientBuilder
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(false)
      .followSslRedirects(false)
      .addInterceptor { chain ->
        chain.proceed(
          chain
            .request()
            .newBuilder()
            .header("User-Agent", "Voice/${appInfoProvider.versionName}")
            .build(),
        )
      }
      .build()

  val authenticatedClient: OkHttpClient =
    client
      .newBuilder()
      .addInterceptor(AccessTokenInterceptor())
      .authenticator(TokenRefresher())
      .build()

  fun clientFor(
    serverUrl: String,
    access: CloudflareAccess?,
  ): OkHttpClient =
    if (access == null) {
      client
    } else {
      client
        .newBuilder()
        .addInterceptor { chain ->
          chain.proceed(chain.request().withCloudflareAccess(serverUrl, access))
        }
        .build()
    }

  fun api(
    serverUrl: String,
    access: CloudflareAccess? = null,
  ): BookOrbitApi = retrofit(serverUrl, clientFor(serverUrl, access))

  fun authenticatedApi(serverUrl: String): BookOrbitApi = retrofit(serverUrl, authenticatedClient)

  fun authenticatedApi(account: Account): BookOrbitApi =
    retrofit(
      account.serverUrl,
      client
        .newBuilder()
        .addInterceptor { chain ->
          chain.proceed(chain.request().newBuilder().tag(Account::class.java, account).build())
        }
        .addInterceptor(AccessTokenInterceptor())
        .authenticator(TokenRefresher())
        .build(),
    )

  fun withToken(account: Account): BookOrbitApi =
    retrofit(
      account.serverUrl,
      clientFor(account.serverUrl, account.cloudflareAccess)
        .newBuilder()
        .addInterceptor { chain ->
          val request = chain.request()
          if (!request.url.inServer(account.serverUrl))
            throw IOException("Request outside the enrolled server")
          chain.proceed(request.withToken(account.accessToken))
        }
        .build(),
    )

  private fun retrofit(
    serverUrl: String,
    client: OkHttpClient,
  ): BookOrbitApi =
    Retrofit.Builder()
      .baseUrl("${serverUrl.trimEnd('/')}/api/v1/")
      .client(client)
      .addConverterFactory(
        object : Converter.Factory() {
          override fun responseBodyConverter(
            type: Type,
            annotations: Array<Annotation>,
            retrofit: Retrofit,
          ): Converter<ResponseBody, *>? {
            if (type != PlaybackState::class.java) return null
            // Retrofit's Java type loses the nullable root returned for an unstarted book.
            return Converter<ResponseBody, PlaybackState?> { body ->
              body.use { bookOrbitJson.decodeFromString<PlaybackState?>(it.string()) }
            }
          }
        },
      )
      .addConverterFactory(bookOrbitJson.asConverterFactory("application/json".toMediaType()))
      .build()
      .create()

  private fun account(): Account? = runBlocking { accountStore.data.first() }

  private inner class AccessTokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
      if (connectionGate.provider.value != "bo")
        throw IOException("Another audiobook server is selected")
      val request = chain.request()
      val account = account()
      val owner = request.tag(Account::class.java)
      if (owner != null && !owner.sameOwner(account)) throw IOException("Server account changed")
      val mediaOwner = request.header("X-Voice-Owner")
      if (
        mediaOwner != null &&
          (account == null || mediaOwner != "${account.instanceId}/${account.userId}")
      ) {
        throw IOException("Server account changed")
      }
      val clean =
        request
          .newBuilder()
          .removeHeader("X-Voice-Owner")
          .apply { if (mediaOwner != null && account != null) tag(Account::class.java, account) }
          .build()
      val updated =
        if (account != null && request.url.inServer(account.serverUrl)) {
          clean
            .withToken(account.accessToken)
            .withCloudflareAccess(account.serverUrl, account.cloudflareAccess)
        } else {
          clean.newBuilder().removeHeader("Authorization").removeCloudflareAccess().build()
        }
      return chain.proceed(updated)
    }
  }

  private inner class TokenRefresher : Authenticator {
    override fun authenticate(
      route: Route?,
      response: Response,
    ): Request? =
      synchronized(this@BookOrbitHttp) {
        if (connectionGate.provider.value != "bo") return null
        val current = account() ?: return null
        val request = response.request
        if (
          !request.url.inServer(current.serverUrl) ||
            current.needsLogin ||
            response.priorResponse != null
        )
          return null
        val owner = request.tag(Account::class.java)
        if (owner != null && !owner.sameOwner(current)) return null
        if (
          request.header("Authorization") != "Bearer ${current.accessToken}" ||
            request.header(CLOUDFLARE_ID) != current.cloudflareAccess?.clientId ||
            request.header(CLOUDFLARE_SECRET) != current.cloudflareAccess?.clientSecret
        ) {
          return request
            .withToken(current.accessToken)
            .withCloudflareAccess(current.serverUrl, current.cloudflareAccess)
        }
        val refreshed =
          try {
            api(current.serverUrl, current.cloudflareAccess)
              .refresh(RefreshRequest(current.refreshToken))
              .execute()
          } catch (_: IOException) {
            return null
          }
        val tokens = refreshed.body()
        if (
          !refreshed.isSuccessful ||
            tokens == null ||
            tokens.accessToken.isBlank() ||
            tokens.refreshToken.isBlank()
        ) {
          if (refreshed.code() == 401 || refreshed.code() == 403) {
            runBlocking {
              accountStore.updateData { if (it == current) it.copy(needsLogin = true) else it }
            }
          }
          return null
        }
        var accepted = false
        runBlocking {
          accountStore.updateData {
            if (it == current) {
              accepted = true
              it.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                needsLogin = false,
              )
            } else {
              it
            }
          }
        }
        if (accepted) request.withToken(tokens.accessToken) else null
      }
  }
}

internal fun HttpUrl.inServer(serverUrl: String): Boolean {
  val root = serverUrl.toHttpUrl()
  return scheme == root.scheme &&
    host == root.host &&
    port == root.port &&
    encodedPath.startsWith("${root.encodedPath.trimEnd('/')}/api/v1/")
}

private fun Request.withToken(token: String): Request =
  newBuilder().header("Authorization", "Bearer $token").build()

private const val CLOUDFLARE_ID = "CF-Access-Client-Id"
private const val CLOUDFLARE_SECRET = "CF-Access-Client-Secret"

private fun Request.Builder.removeCloudflareAccess(): Request.Builder =
  removeHeader(CLOUDFLARE_ID).removeHeader(CLOUDFLARE_SECRET)

private fun Request.withCloudflareAccess(
  serverUrl: String,
  access: CloudflareAccess?,
): Request {
  val builder = newBuilder().removeCloudflareAccess()
  if (access != null) {
    if (!url.isHttps || !url.inServer(serverUrl) || !access.isValid()) {
      throw IOException("Cloudflare Access requires a valid HTTPS endpoint")
    }
    builder.header(CLOUDFLARE_ID, access.clientId).header(CLOUDFLARE_SECRET, access.clientSecret)
  }
  return builder.build()
}
