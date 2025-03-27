package awspurge.resources

class ResourceRegistry {
    val resourceDefinitions = mutableListOf<ResourceDefinition<*>>()

    fun registerResourceDefinition(resourceDefinition: ResourceDefinition<*>) {
        resourceDefinitions.add(resourceDefinition)
    }

    fun close() {
        resourceDefinitions.forEach { it.close() }
    }

}
data class ResourceDefinition<T: Resource>(
    val resourceDeleter: ResourceDeleter,
    val resourceScanner: ResourceScanner<T>,
    val close: () -> Unit = {},
)