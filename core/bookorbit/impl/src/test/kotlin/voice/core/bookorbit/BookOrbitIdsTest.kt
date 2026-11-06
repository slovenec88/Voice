package voice.core.bookorbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import voice.core.data.ChapterId

class BookOrbitIdsTest {

  @Test
  fun `book identities distinguish servers users and books`() {
    val id = BookOrbitIds.bookId(INSTANCE_ID, 7, 42)

    assertEquals(BookOrbitRef(INSTANCE_ID, 7, 42), id.bookRef)
    assertNotEquals(id, BookOrbitIds.bookId(OTHER_INSTANCE_ID, 7, 42))
    assertNotEquals(id, BookOrbitIds.bookId(INSTANCE_ID, 8, 42))
    assertNotEquals(id, BookOrbitIds.bookId(INSTANCE_ID, 7, 43))
  }

  @Test
  fun `instance identity is canonicalized without changing ownership`() {
    assertEquals(
      BookOrbitIds.bookId(INSTANCE_ID, 7, 42),
      BookOrbitIds.bookId(INSTANCE_ID.uppercase(), 7, 42),
    )
  }

  @Test
  fun `a track round trips the asset and its content revision`() {
    val id = BookOrbitIds.chapterId(INSTANCE_ID, 7, 42, 1, SECOND_ASSET_ID, SECOND_ETAG)

    assertEquals(
      BookOrbitTrackRef(BookOrbitRef(INSTANCE_ID, 7, 42), 1, SECOND_ASSET_ID, SECOND_ETAG),
      id.trackRef,
    )
    assertEquals(id.trackRef, trackRef(id.value))
  }

  @Test
  fun `changed audio has a different cache identity`() {
    assertNotEquals(
      BookOrbitIds.chapterId(INSTANCE_ID, 7, 42, 0, FIRST_ASSET_ID, FIRST_ETAG),
      BookOrbitIds.chapterId(INSTANCE_ID, 7, 42, 0, FIRST_ASSET_ID, SECOND_ETAG),
    )
  }

  @Test
  fun `book parser rejects unrelated malformed and traversal identities`() {
    listOf(
        "abs://item/42",
        "https://$INSTANCE_ID/7/book/42",
        "bo://not-an-instance/7/book/42",
        "bo://$INSTANCE_ID/0/book/42",
        "bo://$INSTANCE_ID/7/book/-42",
        "bo://$INSTANCE_ID/7/book/42/extra",
        "bo://$INSTANCE_ID/7/book/../42",
        "bo://$INSTANCE_ID/7/book/42?other=7",
        "bo://$INSTANCE_ID/7/book/42#fragment",
      )
      .forEach { assertNull(bookRef(it)) }
  }

  @Test
  fun `track parser rejects malformed assets hashes and negative sequence`() {
    val id = BookOrbitIds.chapterId(INSTANCE_ID, 7, 42, 0, FIRST_ASSET_ID, FIRST_ETAG)

    assertNull(ChapterId(id.value.replace("/asset/0/", "/asset/-1/")).trackRef)
    assertNull(ChapterId(id.value.replace(FIRST_ASSET_ID, "aud_not-a-uuid")).trackRef)
    assertNull(ChapterId(id.value.dropLast(1)).trackRef)
    assertNull(ChapterId(id.value + "/extra").trackRef)
    assertNull(ChapterId(id.value.replace("/asset/", "/other/")).trackRef)
  }

  @Test
  fun `invalid identities cannot be generated`() {
    assertFailsWith<IllegalArgumentException> { BookOrbitIds.bookId("bad", 7, 42) }
    assertFailsWith<IllegalArgumentException> { BookOrbitIds.bookId(INSTANCE_ID, 0, 42) }
    assertFailsWith<IllegalArgumentException> { BookOrbitIds.bookId(INSTANCE_ID, 7, 0) }
    assertFailsWith<IllegalArgumentException> {
      BookOrbitIds.chapterId(INSTANCE_ID, 7, 42, -1, FIRST_ASSET_ID, FIRST_ETAG)
    }
    assertFailsWith<IllegalArgumentException> {
      BookOrbitIds.chapterId(INSTANCE_ID, 7, 42, 0, "../asset", FIRST_ETAG)
    }
    assertFailsWith<IllegalArgumentException> {
      BookOrbitIds.chapterId(INSTANCE_ID, 7, 42, 0, FIRST_ASSET_ID, "bad")
    }
  }
}
