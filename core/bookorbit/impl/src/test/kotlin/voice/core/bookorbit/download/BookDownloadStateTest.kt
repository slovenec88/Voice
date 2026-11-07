package voice.core.bookorbit.download

import androidx.media3.exoplayer.offline.Download
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import voice.core.audiobookshelf.download.BookDownloadState
import voice.core.audiobookshelf.download.WaitingFor
import voice.core.bookorbit.SECOND_ETAG
import voice.core.bookorbit.sync.chapters
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testBook
import voice.core.bookorbit.testManifest

class BookDownloadStateTest {

  private val book = testBook()
  private val first = book.chapters[0].copy(fileSize = 100)
  private val second = book.chapters[1].copy(fileSize = 300)

  @Test
  fun `all current assets must be completed before a book is offline`() {
    val files = mapOf(first.id.value to file(Download.STATE_COMPLETED, 100))

    assertEquals(
      BookDownloadState.Downloading(0.25F, 100, 400, null),
      bookDownloadState(listOf(first, second), files, null),
    )
    assertEquals(
      BookDownloadState.Downloaded(400),
      bookDownloadState(
        listOf(first, second),
        files + (second.id.value to file(Download.STATE_COMPLETED, 300)),
        null,
      ),
    )
  }

  @Test
  fun `unknown file sizes use completed track count instead of dividing by zero`() {
    val files =
      mapOf(
        first.id.value to file(Download.STATE_COMPLETED, 100),
        second.id.value to file(Download.STATE_DOWNLOADING, 50),
      )

    val state =
      assertIs<BookDownloadState.Downloading>(
        bookDownloadState(listOf(first.copy(fileSize = 0), second), files, WaitingFor.Wifi),
      )

    assertEquals(0.5F, state.progress)
    assertEquals(0L, state.totalBytes)
    assertEquals(150L, state.downloadedBytes)
    assertEquals(WaitingFor.Wifi, state.waitingFor)
  }

  @Test
  fun `changed audio cannot reuse a completed old cache entry`() {
    val manifest = testManifest()
    val changed =
      manifest.copy(
        assets =
          manifest.assets.mapIndexed { index, asset ->
            if (index == 0) {
              asset.copy(etag = SECOND_ETAG)
            } else {
              asset
            }
          },
      )
    val files = book.chapters.associate { it.id.value to file(Download.STATE_COMPLETED, 100) }

    assertIs<BookDownloadState.Downloading>(
      bookDownloadState(changed.chapters(testAccount()), files, null),
    )
  }

  @Test
  fun `a chapter only revision preserves the downloaded state`() {
    val manifest =
      testManifest()
        .copy(
          revision = "b".repeat(64),
          chapters =
            testManifest().chapters.map {
              it.copy(title = "New chapter title")
            },
        )
    val files = book.chapters.associate { it.id.value to file(Download.STATE_COMPLETED, 100) }

    assertEquals(
      BookDownloadState.Downloaded(200),
      bookDownloadState(manifest.chapters(testAccount()), files, null),
    )
  }

  @Test
  fun `removing all tracks hides a stopped download immediately`() {
    val files = book.chapters.associate { it.id.value to file(Download.STATE_REMOVING, 100) }

    assertNull(bookDownloadState(book.chapters, files, null))
  }

  @Test
  fun `one failed asset waits for active assets before reporting failure`() {
    val failed = mapOf(first.id.value to file(Download.STATE_FAILED, 50))

    assertIs<BookDownloadState.Downloading>(
      bookDownloadState(
        listOf(first, second),
        failed + (second.id.value to file(Download.STATE_DOWNLOADING, 30)),
        null,
      ),
    )
    assertEquals(
      BookDownloadState.Failed,
      bookDownloadState(
        listOf(first, second),
        failed + (second.id.value to file(Download.STATE_COMPLETED, 300)),
        null,
      ),
    )
  }

  private fun file(
    state: Int,
    bytes: Long,
  ) = FileDownload(book.id, state, bytes)
}
