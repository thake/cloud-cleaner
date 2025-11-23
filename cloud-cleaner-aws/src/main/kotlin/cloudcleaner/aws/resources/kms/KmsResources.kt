package cloudcleaner.aws.resources.kms

import cloudcleaner.aws.resources.AwsResourceDefinitionFactory

fun kmsResources(): List<AwsResourceDefinitionFactory<*>> = listOf(
    KmsKeyResourceDefinitionFactory(),
    KmsKeyAliasResourceDefinitionFactory()
)

