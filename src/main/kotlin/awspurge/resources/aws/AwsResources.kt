package awspurge.resources.aws

import awspurge.config.Config
import awspurge.resources.Resource
import awspurge.resources.ResourceDefinition
import awspurge.resources.ResourceRegistry

fun loadAwsResources(
    awsConnectionInformation: AwsConnectionInformation,
    resourceTypes: Config.ResourceTypes
): ResourceRegistry {
    val registry = ResourceRegistry()
    val definitions = listOf(
        CloudformationStackResourceDefinitionFactory()
    )
    definitions
        .filter { awsConnectionInformation.region != "global" || it.availableInGlobal }
        .filter { resourceTypes.isIncluded(it.type) }
        .forEach { registry.registerResourceDefinition(it.createResourceDefinition(awsConnectionInformation)) }
    return registry
}

interface AwsResourceDefinitionFactory<T : Resource> {
    val type: String
    val availableInGlobal: Boolean get() = false
    fun createResourceDefinition(
        awsConnectionInformation: AwsConnectionInformation,
    ): ResourceDefinition<T>

}