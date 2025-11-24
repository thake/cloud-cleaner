package cloudcleaner.aws.resources

import aws.sdk.kotlin.services.cloudformation.model.StackResourceSummary
import cloudcleaner.aws.resources.backup.BackupVaultName
import cloudcleaner.aws.resources.cloudformation.StackName
import cloudcleaner.aws.resources.cloudformation.extractStackNameFromStackId
import cloudcleaner.aws.resources.cloudwatch.CloudWatchLogGroupName
import cloudcleaner.aws.resources.dynamodb.DynamoDbTableName
import cloudcleaner.aws.resources.ecr.EcrRepositoryName
import cloudcleaner.aws.resources.iam.IamRoleName
import cloudcleaner.aws.resources.kms.KmsKeyAliasName
import cloudcleaner.aws.resources.kms.KmsKeyId
import cloudcleaner.aws.resources.lambda.LambdaFunctionName
import cloudcleaner.aws.resources.route53.HostedZoneId
import cloudcleaner.aws.resources.ssm.SsmParameterName
import cloudcleaner.resources.Id
import cloudcleaner.resources.StringId

data class Arn(val value: String) : Id {
  init {
    require(value.startsWith("arn:")) { "Invalid ARN format: $value" }
  }

  override fun toString() = value
}

fun idFromCloudFormationStackResourceOrNull(
  stackResourceSummary: StackResourceSummary,
  accountId: String,
  region: String
): Id? {
  val physicalId = stackResourceSummary.physicalResourceId ?: return null
  val cloudformationType = stackResourceSummary.resourceType ?: return null
  return when (cloudformationType) {
    "AWS::IAM::Role" -> IamRoleName(physicalId)
    "AWS::IAM::Policy" -> Arn("arn:aws:iam::$accountId:policy/$physicalId")
    "AWS::IAM::ManagedPolicy" -> Arn(physicalId)
    "AWS::SSM::Parameter" -> SsmParameterName(physicalId, region)
    "AWS::S3::Bucket" -> Arn("arn:aws:s3:::$physicalId")
    "AWS::S3::BucketPolicy" -> null
    "Custom::LogRetention",
    "AWS::Logs::LogGroup" -> CloudWatchLogGroupName(physicalId, region)
    "AWS::Route53::HostedZone" -> HostedZoneId(physicalId)
    "AWS::CloudFormation::Stack" -> StackName(extractStackNameFromStackId(physicalId), region)
    "AWS::ECR::Repository" -> EcrRepositoryName(physicalId, region)
    "AWS::Lambda::Function" -> LambdaFunctionName(physicalId, region)
    "AWS::DynamoDB::Table",
    "AWS::DynamoDB::GlobalTable" -> DynamoDbTableName(physicalId, region)
    "AWS::KMS::Alias" -> KmsKeyAliasName(physicalId, region)
    "AWS::KMS::Key" -> KmsKeyId(physicalId, region)
    "AWS::Backup::BackupVault" -> BackupVaultName(physicalId, region)
    else -> StringId(physicalId)
  }
}
