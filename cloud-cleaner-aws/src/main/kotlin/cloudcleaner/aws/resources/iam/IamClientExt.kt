package cloudcleaner.aws.resources.iam

import aws.sdk.kotlin.services.iam.IamClient
import aws.sdk.kotlin.services.iam.getPolicy
import aws.sdk.kotlin.services.iam.getRole
import aws.sdk.kotlin.services.iam.model.NoSuchEntityException
import aws.sdk.kotlin.services.iam.paginators.listAttachedRolePoliciesPaginated
import aws.sdk.kotlin.services.iam.paginators.listPolicyVersionsPaginated
import aws.sdk.kotlin.services.iam.paginators.listRolePoliciesPaginated
import cloudcleaner.aws.resources.Arn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform

suspend fun IamClient.getInlinePolicyNames(roleName: String): List<String> =
    try {
      listRolePoliciesPaginated { this.roleName = roleName }
          .transform { response -> response.policyNames.forEach { emit(it) } }
          .toList()
    }catch (_: NoSuchEntityException) {
      emptyList()
    }

suspend fun IamClient.getAttachedPolicies(roleName: String): List<Arn> =
    try {
      listAttachedRolePoliciesPaginated { this.roleName = roleName }
          .transform { response ->
            response.attachedPolicies?.mapNotNull { policy -> policy.policyArn?.let { Arn(it) } }?.forEach { emit(it) }
          }
          .toList()
    }catch (_: NoSuchEntityException) {
      emptyList()
    }

suspend fun IamClient.isRoleExisting(roleName: String): Boolean {
  return try {
    this.getRole { this.roleName = roleName }
    true
  } catch (_: NoSuchEntityException) {
    false
  }
}

suspend fun IamClient.isPolicyExisting(policyArn: String): Boolean {
  return try {
    this.getPolicy { this.policyArn = policyArn }
    true
  } catch (_: NoSuchEntityException) {
    false
  }
}

suspend fun IamClient.getPolicyVersions(policyArn: String): List<String> =
    try {
      listPolicyVersionsPaginated { this.policyArn = policyArn }
          .transform { response ->
            response.versions?.filter { !it.isDefaultVersion }?.mapNotNull { it.versionId }?.forEach { emit(it) }
          }
          .toList()
    } catch (_: NoSuchEntityException) {
      emptyList()
    }

