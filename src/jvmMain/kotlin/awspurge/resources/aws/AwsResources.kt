package awspurge.resources.aws

import awspurge.resources.ResourceRegistry

fun loadAwsResources(awsConnectionInformation: AwsConnectionInformation): ResourceRegistry {
    val registry = ResourceRegistry()
    val definitions = listOf(
        createCloudFormationStackResource(awsConnectionInformation)
    )
    definitions.forEach {registry.registerResourceDefinition(it)}
    return registry
}