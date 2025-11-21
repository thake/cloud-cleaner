package cloudcleaner.aws.resources.ssm

import cloudcleaner.aws.resources.AwsResourceDefinitionFactory

fun ssmResources(): List<AwsResourceDefinitionFactory<*>> = listOf(
    SsmParameterResourceDefinitionFactory()
)

