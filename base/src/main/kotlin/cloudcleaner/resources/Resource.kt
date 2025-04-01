package cloudcleaner.resources

import kotlinx.coroutines.flow.Flow

interface Resource {
    val id: Id
    val name: String
    val type: String
    val properties: Map<String, String>
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