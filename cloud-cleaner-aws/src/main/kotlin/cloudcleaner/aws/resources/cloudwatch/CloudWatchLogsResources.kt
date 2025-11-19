package cloudcleaner.aws.resources.cloudwatch

import cloudcleaner.aws.resources.AwsResourceDefinitionFactory

fun cloudWatchLogsResources(): List<AwsResourceDefinitionFactory<*>> = listOf(
    CloudWatchLogGroupResourceDefinitionFactory()
)

