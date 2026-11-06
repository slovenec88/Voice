package voice.features.bookorbit.login

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import voice.core.bookorbit.BookOrbit
import voice.core.bookorbit.BookOrbitConnection
import voice.core.bookorbit.BookOrbitLibrary
import voice.core.bookorbit.BookOrbitServer
import voice.core.bookorbit.CloudflareAccess
import voice.core.bookorbit.FindServerResult
import voice.core.bookorbit.LibrariesPreview
import voice.core.bookorbit.LoginResult
import voice.core.bookorbit.PendingLogin
import voice.core.common.DispatcherProvider
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

class BookOrbitLoginViewModelTest {

  private val scope = TestScope()
  private val server = mockk<BookOrbitServer> { every { passwordLogin } returns true }
  private val pending = mockk<PendingLogin>()
  private val preview =
    LibrariesPreview(
      listOf(BookOrbitLibrary("3", "Fiction", 12), BookOrbitLibrary("5", "Nonfiction", 4)),
      emptyList(),
    )
  private val bookOrbit =
    mockk<BookOrbit>(relaxUnitFun = true) {
      coEvery { findServer(any(), any()) } returns FindServerResult.Found(server)
      coEvery { savedCloudflareAccess(any()) } returns null
      coEvery { login(server, "listener", "secret") } returns LoginResult.Success(pending)
      coEvery { connect(any(), any()) } returns true
      coEvery { preview(pending) } returns this@BookOrbitLoginViewModelTest.preview
      every { connection } returns
        flowOf(BookOrbitConnection("https://books.example.org/", "listener", listOf("3"), true))
    }
  private val navigator = mockk<Navigator>(relaxUnitFun = true)

  @Test
  fun `onboarding connects only selected libraries and advances to completion`() = scope.runTest {
    val viewModel = credentials(origin = Origin.Onboarding)
    viewModel.onLogin()
    runCurrent()
    val libraries = assertIs<BookOrbitLoginViewState.Libraries>(viewModel.state)
    assertEquals(16, libraries.bookCount)
    assertTrue(libraries.canConnect)

    viewModel.onLibraryToggle("5")
    viewModel.onConnect()
    runCurrent()

    coVerify(exactly = 1) {
      val _ = bookOrbit.connect(pending, listOf("3"))
    }
    verify(exactly = 1) { navigator.goTo(Destination.OnboardingCompletion) }
    verify(exactly = 0) { navigator.setRoot(Destination.BookOverview) }
  }

  @Test
  fun `a failed library preview can be retried without another password submission`() =
    scope.runTest {
      coEvery { bookOrbit.preview(pending) } returns null andThen preview
      val viewModel = credentials()
      viewModel.onLogin()
      runCurrent()
      assertTrue(assertIs<BookOrbitLoginViewState.Libraries>(viewModel.state).failed)

      viewModel.onRetryLibraries()
      runCurrent()

      assertFalse(assertIs<BookOrbitLoginViewState.Libraries>(viewModel.state).failed)
      coVerify(exactly = 1) {
        val _ = bookOrbit.login(any(), any(), any())
      }
      coVerify(exactly = 2) {
        val _ = bookOrbit.preview(pending)
      }
    }

  @Test
  fun `deselecting all libraries disables the connection action`() = scope.runTest {
    val viewModel = credentials()
    viewModel.onLogin()
    runCurrent()

    viewModel.onLibraryToggle("3")
    viewModel.onLibraryToggle("5")

    val state = assertIs<BookOrbitLoginViewState.Libraries>(viewModel.state)
    assertEquals(0, state.bookCount)
    assertFalse(state.canConnect)
  }

  @Test
  fun `a connection refused by the active provider gate stays on library selection`() =
    scope.runTest {
      coEvery { bookOrbit.connect(any(), any()) } returns false
      val viewModel = credentials(origin = Origin.Onboarding)
      viewModel.onLogin()
      runCurrent()

      viewModel.onConnect()
      runCurrent()

      val state = assertIs<BookOrbitLoginViewState.Libraries>(viewModel.state)
      assertTrue(state.blockedByOtherProvider)
      assertFalse(state.busy)
      verify(exactly = 0) { navigator.goTo(Destination.OnboardingCompletion) }
      verify(exactly = 0) { navigator.setRoot(any()) }
    }

  @Test
  fun `rejected credentials stay on the login step with an actionable error`() = scope.runTest {
    coEvery { bookOrbit.login(server, "listener", "secret") } returns LoginResult.WrongCredentials
    val viewModel = credentials()

    viewModel.onLogin()
    runCurrent()

    val state = assertIs<BookOrbitLoginViewState.Credentials>(viewModel.state)
    assertEquals(CredentialsError.WrongCredentials, state.error)
    assertFalse(state.busy)
    coVerify(exactly = 0) {
      val _ = bookOrbit.preview(any())
    }
  }

  @Test
  fun `renewal refuses to replace the connected account`() = scope.runTest {
    coEvery { bookOrbit.renewLogin(pending) } returns false
    val viewModel = viewModel(renew = true)
    runCurrent()
    viewModel.onFindServer()
    runCurrent()
    val credentials = assertIs<BookOrbitLoginViewState.Credentials>(viewModel.state)
    assertEquals("listener", credentials.username)
    viewModel.onPasswordChange("secret")

    viewModel.onLogin()
    runCurrent()

    assertEquals(
      CredentialsError.OtherAccount,
      assertIs<BookOrbitLoginViewState.Credentials>(viewModel.state).error,
    )
    coVerify(exactly = 0) {
      val _ = bookOrbit.connect(any(), any())
    }
  }

  @Test
  fun `changing the address during renewal clears the old hosts credentials`() = scope.runTest {
    val access = CloudflareAccess("test-id", "test-secret")
    coEvery { bookOrbit.savedCloudflareAccess("https://books.example.org/") } returns access
    val viewModel = viewModel(renew = true)
    runCurrent()
    assertEquals(access, assertIs<BookOrbitLoginViewState.Address>(viewModel.state).serviceToken)

    viewModel.onAddressChange("https://different.example.org/")
    viewModel.onFindServer()
    runCurrent()

    coVerify(exactly = 1) {
      val _ = bookOrbit.findServer("https://different.example.org/", null)
    }
  }

  @Test
  fun `going back cancels discovery before its response can open credentials`() = scope.runTest {
    coEvery { bookOrbit.findServer(any(), any()) } coAnswers
      {
        delay(10_000)
        FindServerResult.Found(server)
      }
    val viewModel = viewModel()
    viewModel.onAddressChange("books.example.org")
    viewModel.onFindServer()
    runCurrent()

    viewModel.onBack()
    advanceTimeBy(10_001)
    runCurrent()

    assertIs<BookOrbitLoginViewState.Address>(viewModel.state)
    verify(exactly = 1) { navigator.goBack() }
  }

  private fun credentials(origin: Origin = Origin.Default): BookOrbitLoginViewModel {
    val viewModel = viewModel(origin)
    viewModel.onAddressChange("books.example.org")
    viewModel.onFindServer()
    scope.runCurrent()
    viewModel.onUsernameChange("listener")
    viewModel.onPasswordChange("secret")
    return viewModel
  }

  private fun viewModel(
    origin: Origin = Origin.Default,
    renew: Boolean = false,
  ) =
    BookOrbitLoginViewModel(
      bookorbit = bookOrbit,
      navigator = navigator,
      dispatcherProvider =
        DispatcherProvider(scope.coroutineContext, scope.coroutineContext, scope.coroutineContext),
      origin = origin,
      renew = renew,
    )
}
