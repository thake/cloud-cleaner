package cloudcleaner.resources

class ResourceRegistry {
    val resourceDefinitions = mutableListOf<ResourceDefinition<*>>()

    fun registerResourceDefinition(resourceDefinition: ResourceDefinition<*>) {
        resourceDefinitions.add(resourceDefinition)
    }

    fun close() {
        resourceDefinitions.forEach { runCatching { it.close.invoke() } }
    }

}
data class ResourceDefinition<T: Resource>(
    val type: String,
    val resourceDeleter: ResourceDeleter,
    val resourceScanner: ResourceScanner<T>,
    val close: () -> Unit = {},
)
