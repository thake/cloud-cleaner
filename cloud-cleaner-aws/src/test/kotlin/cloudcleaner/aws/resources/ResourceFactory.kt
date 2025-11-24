package cloudcleaner.aws.resources

import aws.sdk.kotlin.services.backup.BackupClient
import aws.sdk.kotlin.services.backup.createBackupVault
import aws.sdk.kotlin.services.cloudformation.CloudFormationClient
import aws.sdk.kotlin.services.cloudformation.createStack
import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.createLogGroup
import aws.sdk.kotlin.services.ec2.Ec2Client
import aws.sdk.kotlin.services.ec2.createVpc
import aws.sdk.kotlin.services.ecr.EcrClient
import aws.sdk.kotlin.services.ecr.createRepository
import aws.sdk.kotlin.services.iam.IamClient
import aws.sdk.kotlin.services.iam.createPolicy
import aws.sdk.kotlin.services.iam.createRole
import aws.sdk.kotlin.services.iam.createUser
import aws.sdk.kotlin.services.kms.KmsClient
import aws.sdk.kotlin.services.kms.createAlias
import aws.sdk.kotlin.services.kms.createKey
import aws.sdk.kotlin.services.route53.Route53Client
import aws.sdk.kotlin.services.route53.createHostedZone
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.createSecret
import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.model.ParameterType
import aws.sdk.kotlin.services.ssm.putParameter
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import kotlin.random.Random

fun randomString(length: Int): String {
  val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  return (1..length).map { allowedChars.random() }.joinToString("")
}

suspend fun CloudFormationClient.createStack(
    outputs: List<String> = emptyList(),
    imports: List<String> = emptyList(),
    roleArn: String? = null
): String {
  val stackName = "stack-${randomString(8)}"
  // create a template body that export the given outputs
  // and import the given imports
  // this is a placeholder, you should replace it with actual template generation logic
  val imports =
      imports
          .mapIndexed { index, it ->
            """
            "Import$index": {
                "Type": "AWS::SSM::Parameter",
                "Properties": {
                    "Name": "/cloudformation/import/${randomString(8)}",
                    "Type": "String",
                    "Value": { "Fn::ImportValue": "$it" }
                }
            }
        """
                .trimIndent()
          }
          .joinToString(",")
          .let { if (it.isNotEmpty()) ",$it" else it }
  val outputs =
      outputs
          .mapIndexed { index, it ->
            """
            "Output$index": {
                "Value": { "Fn::GetAtt": ["DummyResource", "Arn"] }
                "Export": {
                    "Name": "$it"
                }
            }
        """
                .trimIndent()
          }
          .joinToString(",")
          .let { if (it.isNotEmpty()) ",$it" else it }

  val templateBody =
      """
        {
            "AWSTemplateFormatVersion": "2010-09-09",
            "Resources": {
                "DummyResource": {
                    "Type": "AWS::SSM::Parameter",
                    "Properties": {
                        "Name": "/cloudformation/${randomString(8)}",
                        "Type": "String",
                        "Value": "MyValue"
                    }
                }
                $imports
            },
            "Outputs": {
                $outputs
            }
        }
    """
          .trimIndent()
  createStack {
    this.stackName = stackName
    this.templateBody = templateBody
    this.roleArn = roleArn
  }
  return stackName
}

suspend fun CloudWatchLogsClient.createLogGroup(): String {
  val logGroupName = "log-group-${randomString(8)}"
  createLogGroup { this.logGroupName = logGroupName }
  return logGroupName
}

suspend fun Ec2Client.createVpc(): String {
  val cidrBlock = "10.${Random.nextInt(0, 256)}.${Random.nextInt(0, 256)}.0/16"
  val response = createVpc { this.cidrBlock = cidrBlock }
  return response.vpc?.vpcId ?: throw IllegalStateException("VPC creation failed")
}

suspend fun IamClient.createPolicy(): String {
  val policyName = "policy-${randomString(8)}"
  val policyDocument = "{}" // Example policy document
  val response = createPolicy {
    this.policyName = policyName
    this.policyDocument = policyDocument
  }
  return response.policy?.arn ?: throw IllegalStateException("Policy creation failed")
}

suspend fun IamClient.createRole(userArn: String): String {
  val roleName = "role-${randomString(8)}"
  // can be assumed by user
  val assumeRolePolicyDocument =
      """
        {
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Principal": {
                        "AWS": "$userArn"
                    },
                    "Action": "sts:AssumeRole"
                }
            ]
        }
    """
          .trimIndent()
  val response = createRole {
    this.roleName = roleName
    this.assumeRolePolicyDocument = assumeRolePolicyDocument
  }
  return response.role?.arn ?: throw IllegalStateException("Role creation failed")
}

suspend fun IamClient.createUser(): String {
  val userName = "user-${randomString(8)}"
  val response = createUser { this.userName = userName }
  return response.user?.arn!!
}

suspend fun EcrClient.createRepository(): String {
  val repositoryName = "repo-${randomString(8)}"
  val response = createRepository { this.repositoryName = repositoryName }
  return response.repository?.repositoryUri ?: throw IllegalStateException("Repository creation failed")
}

suspend fun Route53Client.createHostedZone(): String {
  val name = "zone-${randomString(8)}.com"
  val callerReference = randomString(12)
  val response = createHostedZone {
    this.name = name
    this.callerReference = callerReference
  }
  return response.hostedZone?.id ?: throw IllegalStateException("Hosted zone creation failed")
}

suspend fun S3Client.createBucket(): String {
  val bucketName = "bucket-${randomString(16)}"
  createBucket { bucket = bucketName }
  return bucketName
}

suspend fun SecretsManagerClient.createSecret(): String {
  val name = "secret-${randomString(8)}"
  val secretString = randomString(16)
  val response = createSecret {
    this.name = name
    this.secretString = secretString
  }
  return response.arn ?: throw IllegalStateException("Secret creation failed")
}

suspend fun SsmClient.createParameter(): String {
  val name = "/param-${randomString(8)}"
  val value = randomString(16)
  putParameter {
    this.name = name
    this.value = value
    type = ParameterType.String
  }
  return name
}

suspend fun BackupClient.createBackupVault(): String {
  val vaultName = "vault-${randomString(8)}"
  val response = createBackupVault { backupVaultName = vaultName }
  return response.backupVaultName ?: throw IllegalStateException("Backup vault creation failed")
}

suspend fun KmsClient.createKeyAlias(): String {
  // First create a KMS key
  val keyResponse = createKey {
    description = "Test key ${randomString(8)}"
  }
  val keyId = keyResponse.keyMetadata?.keyId ?: throw IllegalStateException("Key creation failed")

  // Then create an alias for the key
  val aliasName = "alias/test-${randomString(8)}"
  createAlias {
    this.aliasName = aliasName
    this.targetKeyId = keyId
  }
  return aliasName
}

suspend fun fillAccountWithResources(credentials: CredentialsProvider) {
  val cloudFormationClient = CloudFormationClient {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val cloudWatchLogsClient = CloudWatchLogsClient {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val ec2Client = Ec2Client {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val iamClient = IamClient {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val ecrClient = EcrClient {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val route53Client = Route53Client {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val s3Client = S3Client {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val secretsManagerClient = SecretsManagerClient {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val ssmClient = SsmClient {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val backupClient = BackupClient {
    region = "us-east-1"
    credentialsProvider = credentials
  }
  val kmsClient = KmsClient {
    region = "us-east-1"
    credentialsProvider = credentials
  }

  // Create resources
  cloudFormationClient.createStack()
  cloudWatchLogsClient.createLogGroup()
  ec2Client.createVpc()
  iamClient.createPolicy()
  val user = iamClient.createUser()
  iamClient.createRole(user)
  ecrClient.createRepository()
  route53Client.createHostedZone()
  s3Client.createBucket()
  secretsManagerClient.createSecret()
  ssmClient.createParameter()
  backupClient.createBackupVault()
  kmsClient.createKeyAlias()

  // Close clients
  cloudFormationClient.close()
  cloudWatchLogsClient.close()
  ec2Client.close()
  iamClient.close()
  ecrClient.close()
  route53Client.close()
  s3Client.close()
  secretsManagerClient.close()
  ssmClient.close()
  backupClient.close()
  kmsClient.close()
}
