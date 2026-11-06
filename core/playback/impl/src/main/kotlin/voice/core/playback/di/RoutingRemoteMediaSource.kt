package voice.core.playback.di

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import voice.core.server.RemoteMediaSource

class RoutingRemoteDataSource(
  private val sources: Set<RemoteMediaSource>,
  private val fallback: DataSource.Factory = DefaultHttpDataSource.Factory(),
) : DataSource {
  private var delegate: DataSource? = null
  private val listeners = mutableListOf<TransferListener>()

  override fun addTransferListener(transferListener: TransferListener) {
    listeners += transferListener
    delegate?.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    check(delegate == null)
    val scheme = dataSpec.uri.scheme
    val factory =
      sources.firstOrNull { it.scheme == scheme }?.factory
        ?: if (scheme == "http" || scheme == "https") fallback
        else throw IOException("Unknown remote media source")
    return factory
      .createDataSource()
      .also { source ->
        delegate = source
        listeners.forEach(source::addTransferListener)
      }
      .open(dataSpec)
  }

  override fun read(
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ): Int = checkNotNull(delegate).read(buffer, offset, length)

  override fun getUri(): Uri? = delegate?.uri

  override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

  override fun close() {
    try {
      delegate?.close()
    } finally {
      delegate = null
    }
  }
}

@BindingContainer
@ContributesTo(AppScope::class)
object RoutingRemoteMediaModule {
  @Provides
  @RemoteMediaDataSource
  @SingleIn(AppScope::class)
  fun remoteMediaDataSource(
    sources: Set<@JvmSuppressWildcards RemoteMediaSource>,
  ): DataSource.Factory {
    return DataSource.Factory { RoutingRemoteDataSource(sources) }
  }
}
