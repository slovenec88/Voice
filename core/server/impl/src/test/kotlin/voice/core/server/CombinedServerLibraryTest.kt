package voice.core.server

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import voice.core.audiobookshelf.ServerBookEdit
import voice.core.audiobookshelf.ServerDownloads
import voice.core.audiobookshelf.ServerEdit
import voice.core.audiobookshelf.ServerLibrary
import voice.core.audiobookshelf.ServerReachability
import voice.core.data.BookId

class CombinedServerLibraryTest {
  private val gate = ServerConnectionGate()
  private val abs = mockk<ServerDownloads>(relaxUnitFun = true)
  private val bo = mockk<ServerDownloads>(relaxUnitFun = true)
  private val absLibrary = library()
  private val boLibrary = library()
  private val combined =
    CombinedServerLibrary(
      setOf(provider("abs", abs, absLibrary), provider("bo", bo, boLibrary)),
      gate,
    )

  @Test
  fun `downloads and metadata stay with their selected provider`() = runTest {
    val absBook = BookId("abs://item/7")
    val boBook = BookId("bo://book/7")
    val edit = ServerBookEdit(title = "New title")
    assertTrue(gate.claim("bo"))
    combined.download(absBook)
    combined.download(boBook)
    assertEquals(ServerEdit.NotAllowed, combined.updateBook(absBook, edit))

    gate.release("bo")
    assertTrue(gate.claim("abs"))
    combined.download(boBook)
    combined.download(absBook)
    assertEquals(ServerEdit.Saved, combined.updateBook(absBook, edit))

    coVerify(exactly = 1) { bo.download(boBook) }
    coVerify(exactly = 0) { bo.download(absBook) }
    coVerify(exactly = 1) { abs.download(absBook) }
    coVerify(exactly = 0) { abs.download(boBook) }
  }

  private fun provider(key: String, downloads: ServerDownloads, library: ServerLibrary) =
    object : ServerProvider {
      override val scheme = key
      override val downloads = downloads
      override val library = library
    }

  private fun library() =
    mockk<ServerLibrary> {
      every { syncing } returns MutableStateFlow(false)
      every { reachability } returns MutableStateFlow(ServerReachability.Unknown)
      coEvery { updateBook(any(), any()) } returns ServerEdit.Saved
    }
}
