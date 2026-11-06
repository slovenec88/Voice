package voice.core.bookorbit.download

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.trackRef
import voice.core.server.RemoteMediaSource
import voice.core.server.ServerConnectionGate

@SingleIn(AppScope::class)
@Inject
internal class BookOrbitMedia(
  context: Context,
  http: BookOrbitHttp,
  private val accountStore: AccountStore,
  private val connectionGate: ServerConnectionGate,
) {
  val databaseProvider = StandaloneDatabaseProvider(context)
  val cache =
    SimpleCache(
      File(context.noBackupFilesDir, "bookorbit/downloads"),
      NoOpCacheEvictor(),
      databaseProvider,
    )
  private val upstream: DataSource.Factory =
    ResolvingDataSource.Factory(OkHttpDataSource.Factory(http.authenticatedClient)) { spec ->
      if (connectionGate.provider.value != "bo")
        throw IOException("Another audiobook server is selected")
      val track = trackRef(spec.uri.toString()) ?: throw IOException("Invalid audiobook asset")
      val account = runBlocking { accountStore.data.first() }
      if (account == null || !account.owns(track.book))
        throw IOException("The audiobook belongs to another account")
      spec
        .withUri(
          "${account.serverUrl}api/v1/audiobooks/${track.book.bookId}/assets/${track.assetId}/content"
            .toUri(),
        )
        .withRequestHeaders(
          spec.httpRequestHeaders + ("X-Voice-Owner" to "${account.instanceId}/${account.userId}"),
        )
    }
  private val cachedPlayback: DataSource.Factory =
    CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(upstream)
      .setCacheWriteDataSinkFactory(null)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
  val playbackDataSourceFactory: DataSource.Factory = DataSource.Factory {
    val source = cachedPlayback.createDataSource()
    object : DataSource by source {
      override fun open(dataSpec: DataSpec): Long {
        if (connectionGate.provider.value != "bo")
          throw IOException("Another audiobook server is selected")
        val track =
          trackRef(dataSpec.uri.toString()) ?: throw IOException("Invalid audiobook asset")
        val account = runBlocking { accountStore.data.first() }
        if (account == null || !account.owns(track.book))
          throw IOException("The audiobook belongs to another account")
        return source.open(dataSpec)
      }
    }
  }
  val downloadDataSourceFactory: CacheDataSource.Factory =
    CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
}

@BindingContainer
@ContributesTo(AppScope::class)
object BookOrbitMediaModule {
  @Provides
  @IntoSet
  private fun remoteSource(media: BookOrbitMedia): RemoteMediaSource =
    object : RemoteMediaSource {
      override val scheme: String = "bo"
      override val factory: DataSource.Factory = media.playbackDataSourceFactory
    }
}
