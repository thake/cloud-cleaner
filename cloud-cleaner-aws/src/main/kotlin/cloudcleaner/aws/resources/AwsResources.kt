package cloudcleaner.aws.resources

import cloudcleaner.aws.config.Config
import cloudcleaner.aws.resources.cloudformation.cloudFormationResources
import cloudcleaner.aws.resources.cloudwatch.cloudWatchLogsResources
import cloudcleaner.aws.resources.dynamodb.dynamoDbResources
import cloudcleaner.aws.resources.ecr.ecrResources
import cloudcleaner.aws.resources.iam.iamResources
import cloudcleaner.aws.resources.route53.route53Resources
import cloudcleaner.aws.resources.s3.s3Resources
import cloudcleaner.aws.resources.ssm.ssmResources
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceRegistry

fun ResourceRegistry.addAwsResources(
    awsConnectionInformation: AwsConnectionInformation,
    resourceTypes: Config.ResourceTypes
): ResourceRegistry {
  val definitions =
      cloudFormationResources() +
          cloudWatchLogsResources() +
          dynamoDbResources() +
          ecrResources() +
          iamResources() +
          route53Resources() +
          s3Resources() +
          ssmResources()
  definitions
      .filter { it.isAvailableInRegion(awsConnectionInformation.region) }
      .filter { resourceTypes.isIncluded(it.type) }
      .forEach { registerResourceDefinition(it.createResourceDefinition(awsConnectionInformation)) }
  return this
}

interface AwsResourceDefinitionFactory<T : Resource> {
  val type: String

  fun isAvailableInRegion(region: String): Boolean = region != "global"

  fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<T>
}
