package voice.core.bookorbit.download

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.bookorbit.MemoryBookRepository
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.account.Preferences
import voice.core.bookorbit.account.PreferencesStore
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testConnectionGate

@RunWith(AndroidJUnit4::class)
class DownloadIndexIsolationTest {

  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `BookOrbit manager never adopts Audiobookshelfs default download index`() {
    val noBackup = temporary.newFolder("no-backup")
    val context =
      object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir() = noBackup
      }
    val accountStore = AccountStore(MemoryDataStore<Account?>(testAccount()))
    val gate = testConnectionGate()
    val media =
      BookOrbitMedia(
        context,
        BookOrbitHttp(accountStore, mockk { every { versionName } returns "test" }, gate),
        accountStore,
        gate,
      )
    val request =
      DownloadRequest.Builder(
          "abs://item/book/track/1/file",
          "abs://item/book/track/1/file".toUri(),
        )
        .build()
    val absIndex = DefaultDownloadIndex(media.databaseProvider)
    absIndex.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, 8, 0, 0))
    val downloads =
      BookOrbitDownloads(
        context,
        media,
        MemoryBookRepository(),
        PreferencesStore(MemoryDataStore(Preferences())),
        mockk(relaxUnitFun = true),
        accountStore,
        gate,
      )
    val manager = downloads.downloadManager

    try {
      assertNotNull(absIndex.getDownload(request.id))
      assertNull(manager.downloadIndex.getDownload(request.id))
      assertNotNull(absIndex.getDownload(request.id))
    } finally {
      manager.release()
      media.cache.release()
      media.databaseProvider.close()
    }
  }
}
