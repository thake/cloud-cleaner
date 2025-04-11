package cloudcleaner

import cloudcleaner.config.Filter
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toCollection
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val MAX_CONCURRENCY = 10

class Cleaner(
  val dryRun: Boolean,
  private val resourceRegistry: ResourceRegistry,
  private val excludeFilter: List<Filter>
) {
  suspend fun clean() {
    if (dryRun) {
      logger.info { "====== DRY RUN mode - no resources will be deleted =======" }
    }
    logger.info { "Starting clean" }
    val deleterForType = mutableMapOf<String, ResourceDeleter>()
    logger.info { "Scanning resources ..." }
    val resources = resourceRegistry.resourceDefinitions
        .map { resourceDefinition ->
          logger.info { "Scanning for ${resourceDefinition.type} resources ..." }
          val resources = resourceDefinition.resourceScanner.scan().toCollection(mutableListOf())
          resources.firstOrNull()?.let { deleterForType[it.type] = resourceDefinition.resourceDeleter }
          resources
        }
        .flatten()
    val resourcesToDelete = resources.filter { resource -> excludeFilter.none { it.matches(resource) } }
    if (logger.isInfoEnabled()) {
      val resourcesByType = resources.groupBy { it.type }
      val resourcesToDeleteByType = resourcesToDelete.groupBy { it.type }
      resourcesByType.keys.forEach { type ->
        val total = resourcesByType[type]?.size ?: 0
        val toDelete = resourcesToDeleteByType[type]?.size ?: 0
        val filtered = total - toDelete
        logger.info { "$type: Found $total resources. $filtered have been filtered. $toDelete will be deleted." }
      }
    }
    logger.info { "Identified ${resourcesToDelete.size} resources to be deleted." }
    if (dryRun) {
      val dryRunDeleter = DryRunDeleter()
      deleterForType.keys.forEach { deleterForType[it] = dryRunDeleter }
    }

    deleteResourcesInOrder(resourcesToDelete, deleterForType)
  }

  private suspend fun deleteResourcesInOrder(
    resources: List<Resource>,
    deleterForType: MutableMap<String, ResourceDeleter>
  ) {
    val pendingDeletions = ConcurrentHashMap(resources.associateBy { it.id })
    while (pendingDeletions.isNotEmpty()) {
      val deletedResources = deleteResourcesNoOneDependsOn(pendingDeletions, deleterForType)
      deletedResources.forEach { pendingDeletions.remove(it.id) }
    }
  }

  private suspend fun deleteResourcesNoOneDependsOn(
    pendingDeletions: Map<Id, Resource>,
    deleterForType: Map<String, ResourceDeleter>
  ) = coroutineScope {
    val resourcesToDelete = produceDeletableResources(pendingDeletions)
    (0..<MAX_CONCURRENCY).map {
      async {
        deleteResources(resourcesToDelete, deleterForType)
      }
    }.awaitAll().flatten()
  }

  private suspend fun deleteResources(
    resourcesToDelete: ReceiveChannel<Resource>,
    deleterForType: Map<String, ResourceDeleter>
  ): List<Resource> {
    val deletedResources = mutableListOf<Resource>()
    for (resource in resourcesToDelete) {
      val deleter = deleterForType[resource.type]
        ?: throw IllegalStateException("Could not find deleter for type ${resource.type}")
      logger.info { "Deleting ${resource.id} (${resource.type}) ..." }
      try {
        deleter.delete(resource)
        logger.info { "Deleted ${resource.id} (${resource.type})" }
      } catch (e: Exception) {
        logger.error(e) { "Failed to delete ${resource.id} (${resource.type})" }
        continue
      }
      deletedResources.add(resource)

    }
    return deletedResources
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
