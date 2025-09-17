package cloudcleaner

import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDeleter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger{}
class DryRunDeleter: ResourceDeleter {
    private val currentNumber = AtomicInteger(0)
    init {
        logger.info { "DRY RUN mode. Only printing the order in which resources would be deleted" }
    }
    override suspend fun delete(resource: Resource) {
        logger.debug { "${currentNumber.incrementAndGet()}. ${resource.type} ${resource.id} would be deleted." }
    }
}
