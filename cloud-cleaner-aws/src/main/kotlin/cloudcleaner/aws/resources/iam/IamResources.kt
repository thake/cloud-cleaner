package cloudcleaner.aws.resources.iam

import cloudcleaner.aws.resources.AwsResourceDefinitionFactory

fun iamResources(): List<AwsResourceDefinitionFactory<*>> = listOf(IamRoleResourceDefinitionFactory())
