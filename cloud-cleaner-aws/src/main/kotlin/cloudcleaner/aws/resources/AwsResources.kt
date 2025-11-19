package cloudcleaner.aws.resources

import cloudcleaner.aws.config.Config
import cloudcleaner.aws.resources.cloudformation.cloudFormationResources
import cloudcleaner.aws.resources.dynamodb.dynamoDbResources
import cloudcleaner.aws.resources.iam.iamResources
import cloudcleaner.aws.resources.s3.s3Resources
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceRegistry

fun loadAwsResources(
    awsConnectionInformation: AwsConnectionInformation,
    resourceTypes: Config.ResourceTypes
): ResourceRegistry {
  val registry = ResourceRegistry()
  val definitions = cloudFormationResources() + dynamoDbResources() + iamResources() + s3Resources()
  definitions
      .filter { awsConnectionInformation.region != "global" || it.availableInGlobal }
      .filter { resourceTypes.isIncluded(it.type) }
      .forEach { registry.registerResourceDefinition(it.createResourceDefinition(awsConnectionInformation)) }
  return registry
}

interface AwsResourceDefinitionFactory<T : Resource> {
  val type: String
  val availableInGlobal: Boolean
    get() = false

  fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<T>
}
