package voice.core.bookorbit.api

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import voice.core.bookorbit.FIRST_ASSET_ID
import voice.core.bookorbit.MemoryDataStore
import voice.core.bookorbit.account.Account
import voice.core.bookorbit.account.AccountStore
import voice.core.bookorbit.fixture
import voice.core.bookorbit.http.BookOrbitHttp
import voice.core.bookorbit.sync.bookQuery
import voice.core.bookorbit.testAccount
import voice.core.bookorbit.testConnectionGate
import voice.core.bookorbit.testManifest

class BookOrbitApiTest {

  private val server = MockWebServer()
  private lateinit var api: BookOrbitApi

  @Before
  fun setUp() {
    server.start()
    val account = testAccount(server.url("/books/").toString())
    api =
      BookOrbitHttp(
          AccountStore(MemoryDataStore<Account?>(account)),
          OkHttpClient(),
          testConnectionGate(),
        )
        .authenticatedApi(account)
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun `catalog uses a bare library list and a paged audio query under the server subdirectory`() =
    runTest {
      server.enqueue(
        MockResponse(code = 200, body = fixture("libraries")),
      )
      server.enqueue(
        MockResponse(
          code = 200,
          body =
            """{"items":[{"id":42,"files":[{"id":101,"format":"mp3","role":"content"}]}],"total":1,"page":0,"size":200}""",
        ),
      )
      server.enqueue(MockResponse(code = 200, body = fixture("manifest")))

      assertEquals(3L, api.libraries().single().id)
      assertTrue(api.books(3, bookQuery(0)).items.single().hasAudio)
      assertEquals(42L, api.manifest(42).book.id)

      val requests = List(3) { server.takeRequest(1, TimeUnit.SECONDS)!! }
      assertEquals(
        listOf(
          "/books/api/v1/libraries",
          "/books/api/v1/libraries/3/books",
          "/books/api/v1/audiobooks/42/manifest",
        ),
        requests.map {
          it.url.encodedPath
        },
      )
      assertEquals("POST", requests[1].method)
      val query = Json.parseToJsonElement(requests[1].body!!.utf8()).jsonObject
      assertEquals(
        "0",
        query.getValue("pagination").jsonObject.getValue("page").jsonPrimitive.content,
      )
      val formats =
        query
          .getValue("filter")
          .jsonObject
          .getValue("rules")
          .jsonArray
          .single()
          .jsonObject
          .getValue("value")
          .jsonArray
          .map {
            it.jsonPrimitive.content
          }
          .toSet()
      assertTrue("m4b" in formats && "mp3" in formats)
      assertTrue(requests.all { it.headers["Authorization"] == "Bearer access-one" })
    }

  @Test
  fun `progress uses dedicated revision checked state and explicit read status`() = runTest {
    val update =
      PlaybackUpdate(
        FIRST_ASSET_ID,
        15_000,
        "2026-10-30T10:00:00Z",
        "63ef1a66-72f9-4ff6-a156-4a28024c1c30",
        4,
        testManifest().revision,
      )
    server.enqueue(MockResponse(code = 200, body = "null"))
    server.enqueue(
      MockResponse(
        code = 200,
        body =
          """{"assetId":"$FIRST_ASSET_ID","positionMs":15000,"capturedAt":"${update.capturedAt}",
          "revision":5,"manifestRevision":"${update.manifestRevision}"}""",
      ),
    )
    server.enqueue(
      MockResponse(code = 200, body = """{"status":"read","updatedAt":"2026-10-30T10:01:00Z"}"""),
    )

    assertNull(api.playbackState(42))
    assertEquals(5L, api.updatePlaybackState(42, update).body()!!.revision)
    assertTrue(api.updateStatus(42, StatusUpdate("read")).isSuccessful)

    val requests = List(3) { server.takeRequest(1, TimeUnit.SECONDS)!! }
    assertEquals("/books/api/v1/audiobooks/42/playback-state", requests[0].url.encodedPath)
    assertEquals("PUT", requests[1].method)
    val body = Json.parseToJsonElement(requests[1].body!!.utf8()).jsonObject
    assertEquals(update.operationId, body.getValue("operationId").jsonPrimitive.content)
    assertEquals("4", body.getValue("baseRevision").jsonPrimitive.content)
    assertEquals(update.manifestRevision, body.getValue("manifestRevision").jsonPrimitive.content)
    assertEquals("/books/api/v1/books/42/status", requests[2].url.encodedPath)
    assertEquals("PATCH", requests[2].method)
    assertEquals(
      "read",
      Json.parseToJsonElement(requests[2].body!!.utf8())
        .jsonObject
        .getValue("status")
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun `bookmark UUID routes and listening sessions use the native audiobook contract`() = runTest {
    val bookmark = Json.parseToJsonElement(fixture("bookmarks")).jsonArray.first().jsonObject
    val id = bookmark.getValue("id").jsonPrimitive.content
    server.enqueue(MockResponse(code = 200, body = fixture("bookmarks")))
    server.enqueue(MockResponse(code = 200, body = bookmark.toString()))
    server.enqueue(MockResponse(code = 200, body = bookmark.toString()))
    server.enqueue(MockResponse(code = 204))
    server.enqueue(MockResponse(code = 204))

    assertEquals(2, api.bookmarks(42).size)
    assertTrue(api.createBookmark(42, BookmarkRequest(id, 75_000, "Title")).isSuccessful)
    assertTrue(api.updateBookmark(42, id, BookmarkUpdate("Renamed")).isSuccessful)
    assertTrue(api.deleteBookmark(42, id).isSuccessful)
    assertTrue(
      api
        .session(
          101,
          SessionRequest(
            "70c1ce20-c311-44f7-a3f9-cf48a8d1ba5d",
            "2026-10-30T10:00:00Z",
            "2026-10-30T10:00:15Z",
            15,
          ),
        )
        .isSuccessful,
    )

    val requests = List(5) { server.takeRequest(1, TimeUnit.SECONDS)!! }
    assertEquals(listOf("GET", "POST", "PATCH", "DELETE", "POST"), requests.map { it.method })
    assertEquals("/books/api/v1/audiobooks/42/bookmarks/$id", requests[2].url.encodedPath)
    assertEquals(setOf("title"), Json.parseToJsonElement(requests[2].body!!.utf8()).jsonObject.keys)
    assertEquals("/books/api/v1/books/files/101/sessions", requests[4].url.encodedPath)
    val session = Json.parseToJsonElement(requests[4].body!!.utf8()).jsonObject
    assertEquals("listen", session.getValue("sessionType").jsonPrimitive.content)
    assertEquals("android", session.getValue("source").jsonPrimitive.content)
    assertEquals("15", session.getValue("durationSeconds").jsonPrimitive.content)
  }
}
