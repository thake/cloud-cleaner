package awspurge.resources.aws

import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider

data class AwsConnectionInformation(
    val credentialsProvider: CredentialsProvider,
    val region: String,
)