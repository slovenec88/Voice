package voice.core.playback.di

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.runner.RunWith
import voice.core.server.RemoteMediaSource

@RunWith(AndroidJUnit4::class)
class RoutingRemoteDataSourceTest {

  @Test
  fun `each server scheme opens its own data source without changing byte ranges`() {
    val abs = source()
    val bookOrbit = source()
    val http = source()
    val router =
      RoutingRemoteDataSource(setOf(provider("abs", abs), provider("bo", bookOrbit)), factory(http))
    val request =
      DataSpec.Builder()
        .setUri("bo://instance/7/book/42/asset/0/asset/hash")
        .setPosition(500)
        .setLength(100)
        .build()

    assertEquals(123L, router.open(request))

    verify(exactly = 1) {
      val _ = bookOrbit.open(request)
    }
    verify(exactly = 0) {
      val _ = abs.open(any())
    }
    verify(exactly = 0) {
      val _ = http.open(any())
    }
    router.close()
  }

  @Test
  fun `ordinary HTTP uses the plain fallback and never a server client`() {
    val abs = source()
    val bookOrbit = source()
    val http = source()
    val router =
      RoutingRemoteDataSource(setOf(provider("abs", abs), provider("bo", bookOrbit)), factory(http))
    val request = DataSpec.Builder().setUri("https://example.org/public-book.m4b").build()

    router.open(request)
    router.close()

    verify(exactly = 1) {
      val _ = http.open(request)
    }
    verify(exactly = 0) {
      val _ = bookOrbit.open(any())
    }
    verify(exactly = 0) {
      val _ = abs.open(any())
    }
  }

  @Test
  fun `read URI headers and transfer listeners follow the active delegate`() {
    val source = source()
    val before = mockk<TransferListener>()
    val after = mockk<TransferListener>()
    val router = RoutingRemoteDataSource(setOf(provider("bo", source)))
    val buffer = ByteArray(4)
    every { source.read(buffer, 1, 2) } returns 2
    router.addTransferListener(before)
    router.open(DataSpec.Builder().setUri("bo://book").build())
    router.addTransferListener(after)

    assertEquals(2, router.read(buffer, 1, 2))
    assertEquals(Uri.parse("https://example.org/resolved"), router.uri)
    assertEquals(mapOf("Accept-Ranges" to listOf("bytes")), router.responseHeaders)

    router.close()
    verify(exactly = 1) { source.addTransferListener(before) }
    verify(exactly = 1) { source.addTransferListener(after) }
    verify(exactly = 1) { source.close() }
    assertNull(router.uri)
  }

  @Test
  fun `a closed source can subsequently open the other provider`() {
    val abs = source()
    val bookOrbit = source()
    val router = RoutingRemoteDataSource(setOf(provider("abs", abs), provider("bo", bookOrbit)))

    router.open(DataSpec.Builder().setUri("abs://item/book").build())
    router.close()
    router.open(DataSpec.Builder().setUri("bo://book").build())
    router.close()

    verify(exactly = 1) { abs.close() }
    verify(exactly = 1) { bookOrbit.close() }
  }

  @Test
  fun `failed opens can be closed without leaking the delegate`() {
    val source = source()
    every { source.open(any()) } throws IOException("offline")
    val router = RoutingRemoteDataSource(setOf(provider("bo", source)))

    assertFailsWith<IOException> { router.open(DataSpec.Builder().setUri("bo://book").build()) }
    router.close()

    verify(exactly = 1) { source.close() }
    assertNull(router.uri)
  }

  @Test
  fun `unknown schemes cannot fall through to a provider with unrelated credentials`() {
    val abs = source()
    val http = source()
    val router = RoutingRemoteDataSource(setOf(provider("abs", abs)), factory(http))

    assertFailsWith<IOException> {
      router.open(DataSpec.Builder().setUri("unknown://book").build())
    }
    router.close()

    verify(exactly = 0) {
      val _ = abs.open(any())
    }
    verify(exactly = 0) {
      val _ = http.open(any())
    }
  }

  private fun source(): DataSource =
    mockk(relaxUnitFun = true) {
      every { open(any()) } returns 123L
      every { uri } returns Uri.parse("https://example.org/resolved")
      every { responseHeaders } returns mapOf("Accept-Ranges" to listOf("bytes"))
    }

  private fun factory(source: DataSource): DataSource.Factory = DataSource.Factory { source }

  private fun provider(
    providerScheme: String,
    source: DataSource,
  ): RemoteMediaSource =
    object : RemoteMediaSource {
      override val scheme: String = providerScheme
      override val factory: DataSource.Factory = factory(source)
    }
}
