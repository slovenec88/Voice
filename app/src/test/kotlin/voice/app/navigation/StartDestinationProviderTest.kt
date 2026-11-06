package voice.app.navigation

import android.content.Intent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import voice.core.audiobookshelf.ServerAccount
import voice.core.bookorbit.BookOrbit
import voice.core.data.folders.AudiobookFolders
import voice.core.data.store.CurrentBookStore
import voice.features.onboarding.OnboardingCompletedStore
import voice.navigation.Destination

class StartDestinationProviderTest {

  private val completed = MutableStateFlow(false)
  private val folders = mockk<AudiobookFolders> { coEvery { hasAnyFolders() } returns false }
  private val audiobookshelf = mockk<ServerAccount> { coEvery { isConnected() } returns false }
  private val bookorbit = mockk<BookOrbit> { coEvery { isConnected() } returns false }
  private val intent =
    mockk<Intent> {
      every { getStringExtra(any()) } returns null
      every { getBooleanExtra(any(), any()) } returns false
      every { action } returns null
    }
  private val provider =
    StartDestinationProvider(
      onboardingCompletedStore =
        mockk<OnboardingCompletedStore> { every { data } returns completed },
      audiobookFolders = folders,
      currentBookStore = mockk<CurrentBookStore> { every { data } returns flowOf(null) },
      playerController = mockk(),
      serverAccount = audiobookshelf,
      bookorbit = bookorbit,
      json = Json,
    )

  @Test
  fun `a BookOrbit only connection reaches the library without selecting a device folder`() {
    coEvery { bookorbit.isConnected() } returns true

    assertEquals(listOf(Destination.BookOverview), provider(intent))
  }

  @Test
  fun `a new listener without folders or any server sees onboarding`() {
    assertEquals(listOf(Destination.OnboardingWelcome), provider(intent))
  }

  @Test
  fun `an existing local library still skips onboarding without a server`() {
    coEvery { folders.hasAnyFolders() } returns true

    assertEquals(listOf(Destination.BookOverview), provider(intent))
  }
}
