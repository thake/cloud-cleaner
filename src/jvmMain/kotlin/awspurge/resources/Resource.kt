package awspurge.resources

import kotlinx.coroutines.flow.Flow

interface Resource {
    val id: Id
    val type: String
    val dependsOn: Set<Id>
        get() = emptySet()
    val contains: Set<Id>
        get() = emptySet()
}
interface ResourceDeleter {
    suspend fun delete(resource: Resource)
}
interface ResourceScanner<T: Resource> {
    fun scan(): Flow<T>
}