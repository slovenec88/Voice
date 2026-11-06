package voice.core.bookorbit

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MemoryDataStore<T>(initial: T) : DataStore<T> {

  private val value = MutableStateFlow(initial)
  private val mutex = Mutex()

  override val data: Flow<T>
    get() = value

  override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
    transform(value.value).also { value.value = it }
  }
}
