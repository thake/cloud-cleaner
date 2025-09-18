package cloudcleaner.aws.resources.iam

import aws.sdk.kotlin.services.iam.IamClient
import aws.sdk.kotlin.services.iam.deleteRole
import aws.sdk.kotlin.services.iam.deleteRolePolicy
import aws.sdk.kotlin.services.iam.detachRolePolicy
import aws.sdk.kotlin.services.iam.model.NoSuchEntityException
import aws.sdk.kotlin.services.iam.paginators.listAttachedRolePoliciesPaginated
import aws.sdk.kotlin.services.iam.paginators.listRolesPaginated
import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.AwsConnectionInformation
import cloudcleaner.aws.resources.AwsResourceDefinitionFactory
import cloudcleaner.resources.Id
import cloudcleaner.resources.Resource
import cloudcleaner.resources.ResourceDefinition
import cloudcleaner.resources.ResourceDeleter
import cloudcleaner.resources.ResourceScanner
import cloudcleaner.resources.StringId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import kotlin.time.Duration.Companion.milliseconds

val logger = KotlinLogging.logger {}

typealias RoleName = StringId

private const val TYPE = "IamRole"

data class IamRole(val roleName: RoleName, val roleArn: Arn, private val dependencies: Set<Id> = emptySet()) : Resource {
  override val id: Id = roleName
  override val name: String = roleName.value
  override val type: String = TYPE
  override val properties: Map<String, String> =
      mapOf(
          "roleName" to roleName.value,
          "roleArn" to roleArn.value,
      )
  override val dependsOn: Set<Id> = dependencies
  override val contains: Set<Id> = emptySet()
}

class IamRoleResourceDefinitionFactory : AwsResourceDefinitionFactory<IamRole> {
  override val type: String = TYPE
  override val availableInGlobal: Boolean = true

  override fun createResourceDefinition(
      awsConnectionInformation: AwsConnectionInformation,
  ): ResourceDefinition<IamRole> {
    val client = IamClient {
      credentialsProvider = awsConnectionInformation.credentialsProvider
      region = awsConnectionInformation.region
      retryStrategy {
        maxAttempts = 99
        delayProvider { initialDelay = 400.milliseconds }
      }
    }
    return ResourceDefinition(
        type = TYPE,
        resourceDeleter = IamRoleDeleter(client),
        resourceScanner = IamRoleScanner(client),
        close = { client.close() },
    )
  }
}

class IamRoleScanner(private val iamClient: IamClient) : ResourceScanner<IamRole> {
  override fun scan(): Flow<IamRole> = flow {
    iamClient.listRolesPaginated().collect { response ->
      response.roles.forEach { role ->
        val roleName = RoleName(role.roleName)
        val roleArn = Arn(role.arn)

        // Get attached managed policies as dependencies
        val dependencies =
            try {
              iamClient
                  .listAttachedRolePoliciesPaginated { this.roleName = roleName.value }
                  .transform { response ->
                    response.attachedPolicies?.mapNotNull { policy -> policy.policyArn?.let { Arn(it) } }?.forEach { emit(it) }
                  }
                  .toList()
                  .toMutableSet()
            } catch (e: Exception) {
              logger.warn(e) { "Failed to list attached policies for role ${roleName.value}: ${e.message}" }
              mutableSetOf()
            }
        // Get permission boundaries
        role.permissionsBoundary?.permissionsBoundaryArn?.let { dependencies.add(Arn(it)) }

        emit(
            IamRole(
                roleName = roleName,
                roleArn = roleArn,
                dependencies = dependencies,
            ),
        )
      }
    }
  }
}

class IamRoleDeleter(private val iamClient: IamClient) : ResourceDeleter {
  override suspend fun delete(resource: Resource) {
    val role = resource as? IamRole ?: throw IllegalArgumentException("Resource not an IamRole")
    val roleName = role.roleName.value
    if (!iamClient.isRoleExisting(roleName)) {
      return
    }
    try {
      // First, detach all managed policies
      val attachedPolicies = iamClient.getAttachedPolicies(roleName)

      attachedPolicies.forEach { policyArn ->
        iamClient.detachRolePolicy {
          this.roleName = roleName
          this.policyArn = policyArn.value
        }
      }

      // Delete inline policies
      val inlinePolicyNames = iamClient.getInlinePolicyNames(roleName)

      inlinePolicyNames.forEach { policyName ->
        iamClient.deleteRolePolicy {
          this.roleName = roleName
          this.policyName = policyName
        }
      }

      // Finally, delete the role
      logger.info { "Deleting IAM role $roleName" }
      iamClient.deleteRole { this.roleName = roleName }
    } catch(_: NoSuchEntityException) {
      // Role already deleted
      logger.debug { "Deletion failed because IAM role $roleName already has been deleted." }
    } catch (e: Exception) {
      logger.error(e) { "Failed to delete IAM role $roleName: ${e.message}" }
      throw e
    }
  }
}

