package awspurge.resources.aws

import aws.sdk.kotlin.services.cloudformation.model.StackResourceSummary
import awspurge.resources.Id
import awspurge.resources.StringId

data class Arn(val value: String) : Id {
    init {
        require(value.startsWith("arn:")) { "Invalid ARN format: $value" }
    }

    override fun isMatchedBy(string: String) = this.value == string
    override fun toString() = value
}

fun idFromCloudFormationStackResourceOrNull(stackResourceSummary: StackResourceSummary, accountId: String, region: String): Id? {
    val physicalId = stackResourceSummary.physicalResourceId ?: return null
    val cloudformationType = stackResourceSummary.resourceType ?: return null
    return when (cloudformationType) {
        "AWS::IAM::Role" -> Arn("arn:aws:iam::$accountId:role/$physicalId")
        "AWS::IAM::Policy" -> Arn("arn:aws:iam::$accountId:policy/$physicalId")
        "AWS::SSM::Parameter" -> Arn("arn:aws:ssm:$region:$accountId:parameter$physicalId")
        "AWS::ECR::Repository" -> StringId("$accountId.dkr.ecr.$region.amazonaws.com/$physicalId")
        "AWS::S3::Bucket" -> Arn("arn:aws:s3:::$physicalId")
        "AWS::S3::BucketPolicy" -> null
        "AWS::CloudFormation::Stack" -> Arn(physicalId)
        else -> StringId(physicalId)
    }
}