package voice.core.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteBookIdTest {

  @Test
  fun `Audiobookshelf identities remain remote`() {
    assertTrue(BookId("abs://item/existing-book").isRemote)
  }

  @Test
  fun `BookOrbit identities are remote`() {
    assertTrue(BookId("bo://00f8c089-7bb8-4fb2-9faf-fad5fb86a03a/7/book/42").isRemote)
  }

  @Test
  fun `local content stays local even when its path contains a server scheme`() {
    assertFalse(
      BookId("content://com.android.externalstorage.documents/tree/primary%3Abo%3Abooks").isRemote,
    )
    assertFalse(BookId("file:///storage/emulated/0/abs/audiobook.m4b").isRemote)
  }

  @Test
  fun `unrelated URI schemes do not gain server book actions`() {
    assertFalse(BookId("https://example.org/book.m4b").isRemote)
    assertFalse(BookId("books://42").isRemote)
    assertFalse(BookId("absolute://42").isRemote)
    assertFalse(BookId("boomerang://42").isRemote)
  }
}
