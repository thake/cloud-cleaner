package cloudcleaner.aws.resources.iam

import aws.sdk.kotlin.services.iam.IamClient
import aws.sdk.kotlin.services.iam.deletePolicy
import aws.sdk.kotlin.services.iam.deletePolicyVersion
import aws.sdk.kotlin.services.iam.model.NoSuchEntityException
import aws.sdk.kotlin.services.iam.model.PolicyScopeType.Local
import aws.sdk.kotlin.services.iam.paginators.listPoliciesPaginated
import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

val policyLogger = KotlinLogging.logger {}

private const val TYPE = "IamPolicy"

data class IamPolicy(val policyName: String, val policyArn: Arn, private val dependencies: Set<Id> = emptySet()) : Resource {
  override val id: Arn = policyArn
  override val name: String = policyName
  override val type: String = TYPE
  override val properties: Map<String, String> = emptyMap()
  override val dependsOn: Set<Id> = dependencies
  override val containedResources: Set<Id> = emptySet()
}

class IamPolicyResourceDefinitionFactory : AwsResourceDefinitionFactory<IamPolicy> {
  override val type: String = TYPE

  override fun isAvailableInRegion(region: String) = "global" == region

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<IamPolicy> {
    val client = IamClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = IamPolicyDeleter(client),
        resourceScanner = IamPolicyScanner(client),
        close = { client.close() },
    )
  }
}

class IamPolicyScanner(private val iamClient: IamClient) : ResourceScanner<IamPolicy> {
  override fun scan(): Flow<IamPolicy> = flow {
    iamClient.listPoliciesPaginated {
      // Only scan customer managed policies (not AWS managed policies)
      scope = Local
    }.collect { response ->
      response.policies?.forEach { policy ->
        val policyName = policy.policyName ?: return@forEach
        val policyArnString = policy.arn ?: return@forEach
        val policyArn = Arn(policyArnString)

        emit(
            IamPolicy(
                policyName = policyName,
                policyArn = policyArn,
                dependencies = emptySet(),
            ),
        )
      }
    }
  }
}

class IamPolicyDeleter(private val iamClient: IamClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val policy = resource as? IamPolicy ?: throw IllegalArgumentException("Resource not an IamPolicy")
    val policyArn = policy.policyArn
    if (!iamClient.isPolicyExisting(policyArn.value)) {
      return
    }
    try {
      val policyVersions = iamClient.getPolicyVersions(policyArn.value)

      policyVersions.forEach { versionId ->
        iamClient.deletePolicyVersion {
          this.policyArn = policyArn.value
          this.versionId = versionId
        }
      }
      iamClient.deletePolicy { this.policyArn = policyArn.value }
    } catch (_: NoSuchEntityException) {
      policyLogger.debug { "Deletion failed because IAM policy ${policy.policyName} already has been deleted." }
    } catch (e: Exception) {
      policyLogger.error(e) { "Failed to delete IAM policy ${policy.policyName}: ${e.message}" }
      throw e
    }
  }
}

