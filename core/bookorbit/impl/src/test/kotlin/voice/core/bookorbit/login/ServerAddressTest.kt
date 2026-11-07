package voice.core.bookorbit.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerAddressTest {

  @Test
  fun `public hosts require HTTPS by default and local hosts prefer LAN connections`() {
    assertEquals(listOf("https://books.example.org/"), serverUrlCandidates("books.example.org"))
    assertEquals(
      listOf("https://10.0.0.1.example.org/"),
      serverUrlCandidates("10.0.0.1.example.org"),
    )
    assertEquals(listOf("https://[2001:db8::1]/"), serverUrlCandidates("[2001:db8::1]"))
    assertEquals("http://192.168.1.10:6060/", serverUrlCandidates("192.168.1.10:6060").first())
    assertEquals("http://bookorbit.local/", serverUrlCandidates("bookorbit.local").first())
    assertEquals("http://100.101.102.103/", serverUrlCandidates("100.101.102.103").first())
  }

  @Test
  fun `an explicit scheme is respected`() {
    assertEquals(
      listOf("http://books.example.org/"),
      serverUrlCandidates("http://books.example.org"),
    )
    assertEquals(listOf("https://192.168.1.10/"), serverUrlCandidates("https://192.168.1.10"))
  }

  @Test
  fun `reverse proxy and pasted login pages retain their enclosing roots`() {
    assertEquals(
      listOf(
        "https://example.org/books/login/",
        "https://example.org/books/",
        "https://example.org/",
      ),
      serverUrlCandidates(" https://example.org/books/login?redirect=library#section "),
    )
  }

  @Test
  fun `an API URL is normalized to the server root without duplicating the version prefix`() {
    assertEquals(
      listOf("https://example.org/books/", "https://example.org/"),
      serverUrlCandidates("https://example.org/books/api/v1/libraries?token=discard#fragment"),
    )
  }

  @Test
  fun `IPv6 loopback and private hosts are usable without a scheme`() {
    assertEquals("http://[::1]:6060/", serverUrlCandidates("[::1]:6060").first())
    assertEquals("http://[fd00::1]:6060/", serverUrlCandidates("[fd00::1]:6060").first())
    assertEquals("http://localhost:6060/", serverUrlCandidates("localhost:6060").first())
  }

  @Test
  fun `blank or unsupported addresses never become requests`() {
    assertEquals(emptyList(), serverUrlCandidates(" \t "))
    assertEquals(emptyList(), serverUrlCandidates("ftp://example.org"))
    assertTrue(
      serverUrlCandidates("https://example.org/books/").distinct() ==
        serverUrlCandidates("https://example.org/books/"),
    )
  }
}
