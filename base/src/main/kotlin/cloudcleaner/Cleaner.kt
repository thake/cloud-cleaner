package cloudcleaner

import cloudcleaner.config.Filter
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.withLoggingContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val MAX_CONCURRENCY = 10
private const val MAX_SCAN_CONCURRENCY = 10

class Cleaner(
    val dryRun: Boolean,
    private val resourceRegistry: ResourceRegistry,
    private val excludeFilters: List<Filter>,
    private val includeFilters: List<Filter>
) {
  suspend fun clean() {
    if (dryRun) {
      logger.info { "====== DRY RUN mode - no resources will be deleted =======" }
    }
    logger.info { "Starting clean" }
    logger.info { "Scanning resources ..." }
    val scanResult = scanResources(resourceRegistry)
    val resources = scanResult.resources
    var resourcesToDelete =
        resources
            .filter { resource -> includeFilters.isEmpty() || includeFilters.any { it.matches(resource) } }
            .filter { resource -> excludeFilters.none { it.matches(resource) } }
    val resourcesToDeleteSet = resourcesToDelete.toSet()
    var filteredOutResources = resources.filter { it !in resourcesToDeleteSet }
    val containedInFilteredOurResources = filteredOutResources.flatMap { it.containedResources }.toSet()
    resourcesToDelete = resourcesToDelete.filter { it.id !in containedInFilteredOurResources }
    filteredOutResources = resources.filter { it.id !in resourcesToDelete.map { it.id }.toSet() }
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
    val deleterForResource = scanResult.deleterForResource.toMutableMap()
    if (dryRun) {
      val dryRunDeleter = DryRunDeleter()
      deleterForResource.keys.forEach { deleterForResource[it] = dryRunDeleter }
    }

    deleteResourcesInOrder(resourcesToDelete, deleterForResource)
    logger.info {
      val report: String =
          "Filtered out resources: \n" + filteredOutResources.joinToString(separator = "\n") { "\t- ${it.id} (${it.type})" }
      "Clean completed.\nReport:\n$report"
    }
  }

  data class ScanResult(
      val resources: Collection<Resource>,
      val deleterForResource: Map<Resource, ResourceDeleter>,
  )

  suspend fun scanResources(resourceRegistry: ResourceRegistry) = coroutineScope {
    val resourceDefinitions = produceResourceDefinitions(resourceRegistry)
    val workerResults =
        (0..<MAX_SCAN_CONCURRENCY).map {
          async {
            val scanResults = mutableListOf<ScanResult>()
            for (resourceDefinition in resourceDefinitions) {
              logger.info { "Scanning for ${resourceDefinition.type} resources ..." }
              val resources = resourceDefinition.resourceScanner.scan().toCollection(mutableListOf())
              scanResults.add(ScanResult(resources, resources.associateWith { resourceDefinition.resourceDeleter }))
            }
            scanResults
          }
        }
    val allResources = mutableListOf<Resource>()
    val deleterForType = mutableMapOf<Resource, ResourceDeleter>()
    workerResults.awaitAll().forEach { scanResults ->
      scanResults.forEach { scanResult ->
        allResources.addAll(scanResult.resources)
        deleterForType.putAll(scanResult.deleterForResource)
      }
    }
    ScanResult(allResources, deleterForType)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  fun CoroutineScope.produceResourceDefinitions(resourceRegistry: ResourceRegistry) = produce {
    resourceRegistry.resourceDefinitions.forEach { send(it) }
  }

  private suspend fun deleteResourcesInOrder(resources: List<Resource>, deleterForResource: Map<Resource, ResourceDeleter>) {
    val pendingDeletions = ConcurrentHashMap(resources.associateBy { it.id })
    var iteration = 1
    while (pendingDeletions.isNotEmpty()) {
      withLoggingContext("iteration" to iteration.toString()) {
        withContext(MDCContext()) {
          val deletedResources = deleteResourcesNoOneDependsOn(pendingDeletions, deleterForResource)
          deletedResources.forEach { pendingDeletions.remove(it.id) }
        }
      }
      iteration++
    }
  }

  private suspend fun deleteResourcesNoOneDependsOn(pendingDeletions: Map<Id, Resource>, deleterForResource: Map<Resource, ResourceDeleter>) =
      coroutineScope {
        val resourcesToDelete = produceDeletableResources(pendingDeletions)
        (0..<MAX_CONCURRENCY).map { async { deleteResources(resourcesToDelete, deleterForResource) } }.awaitAll().flatten()
      }

  private suspend fun deleteResources(
    resourcesToDelete: ReceiveChannel<Resource>,
    deleterForResource: Map<Resource, ResourceDeleter>
  ): List<Resource> {
    val deletedResources = mutableListOf<Resource>()
    for (resource in resourcesToDelete) {
      val deleter = deleterForResource[resource] ?: throw IllegalStateException("Could not find deleter for ${resource.id}")
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
  val allContainedResources = pendingDeletions.values.flatMap { it.containedResources }.toSet()
  // handle direct dependencies
  allDependencies.forEach { dep -> resourcesToBeDeleted.remove(dep) }
  // handle resources that are contained in other resources
  allContainedResources.forEach { contained -> resourcesToBeDeleted.remove(contained) }
  // handle indirect dependencies
  resourcesToBeDeleted
      .filterValues { resource -> resource.containedResources.any { it in allDependencies } }
      .keys
      .forEach { resourcesToBeDeleted.remove(it) }
  logger.info { "New Iteration: ${resourcesToBeDeleted.size} dependencies to be deleted (total remaining: ${pendingDeletions.size})." }
  resourcesToBeDeleted.values.forEach { send(it) }
}
