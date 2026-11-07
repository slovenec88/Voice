package voice.core.bookorbit

import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.ChapterRepo

internal class MemoryChapterRepo(initial: List<Chapter> = emptyList()) : ChapterRepo {

  private val values = initial.associateBy { it.id }.toMutableMap()

  override suspend fun get(id: ChapterId): Chapter? = values[id]

  override suspend fun get(ids: List<ChapterId>): List<Chapter> = ids.mapNotNull(values::get)

  override suspend fun put(chapter: Chapter) {
    values[chapter.id] = chapter
  }
}
