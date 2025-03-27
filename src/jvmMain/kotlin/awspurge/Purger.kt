package awspurge

import awspurge.resources.Id
import awspurge.resources.Resource
import awspurge.resources.ResourceDeleter
import awspurge.resources.ResourceRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val MAX_CONCURRENCY = 10

class Purger(
    val dryRun: Boolean,
    val resourceRegistry: ResourceRegistry
) {
    suspend fun purge() {
        if (dryRun) {
            logger.info { "====== DRY RUN mode - no resources will be deleted =======" }
        }
        logger.info { "Starting purge" }

        val deleterForType = mutableMapOf<String, ResourceDeleter>()
        val resources = resourceRegistry.resourceDefinitions
            .map { resourceDefinition ->
                val resources = resourceDefinition.resourceScanner.scan().toCollection(mutableListOf())
                resources.firstOrNull()?.let { deleterForType[it.type] = resourceDefinition.resourceDeleter }
                resources
            }
            .flatten()
            .associateBy { it.id }
        logger.info { "Identified ${resources.size} resources to be deleted." }
        if (dryRun) {
            val dryRunDeleter = DryRunDeleter()
            deleterForType.keys.forEach { deleterForType[it] = dryRunDeleter }
        }

        val pendingDeletions = ConcurrentHashMap(resources)
        while (pendingDeletions.isNotEmpty()) {
            coroutineScope {
                val resourcesToDelete = produceDeletableResources(pendingDeletions)
                repeat(MAX_CONCURRENCY) {
                    launch {
                        for (resource in resourcesToDelete) {
                            val deleter = deleterForType[resource.type]
                                ?: throw IllegalStateException("Could not find deleter for type ${resource.type}")
                            deleter.delete(resource)
                            pendingDeletions.remove(resource.id)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun CoroutineScope.produceDeletableResources(pendingDeletions: Map<Id, Resource>) = produce {
    val resourcesToBeDeleted = pendingDeletions.toMutableMap()
    val allDependencies = pendingDeletions.values.flatMap { it.dependsOn }.toSet()
    // handle direct dependencies
    allDependencies.forEach { dep -> resourcesToBeDeleted.remove(dep) }
    // handle indirect dependencies
    resourcesToBeDeleted.filterValues { resource -> resource.contains.any { it in allDependencies } }
        .keys.forEach { resourcesToBeDeleted.remove(it) }
    logger.info { "This iteration deletes ${resourcesToBeDeleted.size} dependencies." }
    resourcesToBeDeleted.values.forEach { send(it) }
}