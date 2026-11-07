package voice.core.bookorbit.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import retrofit2.Response
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.api.BoUser
import voice.core.bookorbit.api.BookOrbitApi
import voice.core.bookorbit.api.ServerIdentity
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testConnectionGate

@RunWith(AndroidJUnit4::class)
class BookOrbitNetworkSyncTest {
  private val account = testAccount()
  private val active = mockk<Network>()
  private val validated =
    mockk<NetworkCapabilities> {
      every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
    }
  private val callback = slot<ConnectivityManager.NetworkCallback>()
  private val manager =
    mockk<ConnectivityManager> {
      every { activeNetwork } returns active
      every { getNetworkCapabilities(active) } returns validated
      every { registerDefaultNetworkCallback(capture(callback)) } just Runs
    }
  private val context =
    mockk<Context> {
      every { getSystemService(ConnectivityManager::class.java) } returns manager
    }
  private val firstRequest = CompletableDeferred<Unit>()
  private val release = CompletableDeferred<Unit>()
  private val secondRequest = CompletableDeferred<Unit>()
  private var requests = 0
  private val publicApi =
    mockk<BookOrbitApi> {
      coEvery { identity() } coAnswers
        {
          if (++requests == 1) firstRequest.complete(Unit) else secondRequest.complete(Unit)
          release.await()
          Response.success(ServerIdentity("bookorbit", 1, account.instanceId))
        }
    }
  private val authorizedApi = mockk<BookOrbitApi> { coEvery { me() } returns BoUser(7, "listener") }
  private val http =
    mockk<BookOrbitHttp> {
      every { api(account.serverUrl) } returns publicApi
      every { authenticatedApi(account) } returns authorizedApi
    }
  private val library = mockk<LibrarySync>(relaxUnitFun = true)

  @Test
  fun `the initial validated callback does not repeat startup but reconnect still syncs`() =
    runTest {
      val sync = sync()
      sync.start()
      firstRequest.await()
      callback.captured.onCapabilitiesChanged(active, validated)
      finish(sync)
      coVerify(exactly = 1) { library.sync(account) }

      callback.captured.onLost(active)
      callback.captured.onCapabilitiesChanged(active, validated)
      secondRequest.await()
      finish(sync)
      coVerify(exactly = 2) { library.sync(account) }
    }

  @Test
  fun `a forced account refresh during startup still queues another pass`() = runTest {
    val sync = sync()
    sync.start()
    firstRequest.await()
    callback.captured.onCapabilitiesChanged(active, validated)
    sync.sync(force = true)
    finish(sync)
    coVerify(exactly = 2) { library.sync(account) }
  }

  @Test
  fun `a network that first becomes validated after startup still forces sync`() = runTest {
    every { manager.getNetworkCapabilities(active) } returns
      mockk {
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false
      }
    val sync = sync()
    sync.start()
    firstRequest.await()
    callback.captured.onCapabilitiesChanged(active, validated)
    finish(sync)
    coVerify(exactly = 2) { library.sync(account) }
  }

  private suspend fun finish(sync: BookOrbitSync) {
    release.complete(Unit)
    sync.syncing.first { !it }
    sync.cancel()
  }

  private fun sync() =
    BookOrbitSync(
      AccountStore(MemoryDataStore<Account?>(account)),
      http,
      library,
      mockk(relaxUnitFun = true),
      mockk(relaxUnitFun = true),
      mockk(relaxUnitFun = true),
      mockk(relaxUnitFun = true),
      mockk { every { flow() } returns flowOf(emptyList()) },
      mockk(),
      mockk(),
      mockk(),
      context,
      testConnectionGate(),
    )
}
