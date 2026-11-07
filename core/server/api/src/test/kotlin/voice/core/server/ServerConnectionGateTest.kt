package voice.core.server

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerConnectionGateTest {

  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `concurrent connection attempts cannot claim two different providers`() = runTest {
    val gate = ServerConnectionGate()

    val results =
      listOf("abs", "bo")
        .map { scheme -> async(Dispatchers.Default) { gate.claim(scheme) } }
        .awaitAll()

    assertEquals(1, results.count { it })
    assertTrue(gate.provider.value in setOf("abs", "bo"))
  }

  @Test
  fun `the other provider cannot release an active connection`() = runTest {
    val gate = ServerConnectionGate()
    assertTrue(gate.claim("abs"))

    gate.release("bo")

    assertEquals("abs", gate.provider.value)
    assertFalse(gate.claim("bo"))
    assertTrue(gate.claim("abs"))
  }

  @Test
  fun `a deliberate signout releases the provider for a later connection`() = runTest {
    val gate = ServerConnectionGate()
    assertTrue(gate.claim("abs"))

    gate.release("abs")

    assertNull(gate.provider.value)
    assertTrue(gate.claim("bo"))
    assertEquals("bo", gate.provider.value)
  }

  @Test
  fun `an existing BookOrbit account reserves the gate before asynchronous startup`() = runTest {
    val accountFile = temporary.root.resolve("bookorbit/account.json")
    checkNotNull(accountFile.parentFile).mkdirs()
    accountFile.writeText("""{"serverUrl":"https://books.example.org/"}""")
    val context = mockk<Context> { every { filesDir } returns temporary.root }

    val gate = ServerConnectionGate(context)

    assertEquals("bo", gate.provider.value)
    assertFalse(gate.claim("abs"))
    assertTrue(gate.claim("bo"))
  }

  @Test
  fun `legacy files from both providers select only one provider without deleting either account`() =
    runTest {
      listOf("audiobookshelf", "bookorbit").forEach { directory ->
        val file = temporary.root.resolve("$directory/account.json")
        checkNotNull(file.parentFile).mkdirs()
        file.writeText("""{"serverUrl":"https://books.example.org/"}""")
      }
      val context = mockk<Context> { every { filesDir } returns temporary.root }

      val gate = ServerConnectionGate(context)

      assertEquals("abs", gate.provider.value)
      assertFalse(gate.claim("bo"))
      assertTrue(temporary.root.resolve("audiobookshelf/account.json").exists())
      assertTrue(temporary.root.resolve("bookorbit/account.json").exists())
    }
}
