package cloudcleaner.aws.resources.route53

import cloudcleaner.aws.resources.AwsResourceDefinitionFactory

fun route53Resources(): List<AwsResourceDefinitionFactory<*>> = listOf(
    Route53HostedZoneResourceDefinitionFactory()
)

