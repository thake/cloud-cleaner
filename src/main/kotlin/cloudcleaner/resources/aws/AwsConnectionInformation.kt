package cloudcleaner.resources.aws

import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider

data class AwsConnectionInformation(
    val accountId: String,
    val credentialsProvider: CredentialsProvider,
    val region: String,
)