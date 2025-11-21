package cloudcleaner.aws.resources.iam

import aws.sdk.kotlin.services.iam.IamClient
import aws.sdk.kotlin.services.iam.model.AttachedPermissionsBoundary
import aws.sdk.kotlin.services.iam.model.AttachedPolicy
import aws.sdk.kotlin.services.iam.model.CreateRoleRequest
import aws.sdk.kotlin.services.iam.model.CreateRoleResponse
import aws.sdk.kotlin.services.iam.model.DeletePolicyRequest
import aws.sdk.kotlin.services.iam.model.DeletePolicyResponse
import aws.sdk.kotlin.services.iam.model.DeletePolicyVersionRequest
import aws.sdk.kotlin.services.iam.model.DeletePolicyVersionResponse
import aws.sdk.kotlin.services.iam.model.DeleteRolePolicyRequest
import aws.sdk.kotlin.services.iam.model.DeleteRolePolicyResponse
import aws.sdk.kotlin.services.iam.model.DeleteRoleRequest
import aws.sdk.kotlin.services.iam.model.DeleteRoleResponse
import aws.sdk.kotlin.services.iam.model.DetachRolePolicyRequest
import aws.sdk.kotlin.services.iam.model.DetachRolePolicyResponse
import aws.sdk.kotlin.services.iam.model.GetPolicyRequest
import aws.sdk.kotlin.services.iam.model.GetPolicyResponse
import aws.sdk.kotlin.services.iam.model.GetRoleRequest
import aws.sdk.kotlin.services.iam.model.GetRoleResponse
import aws.sdk.kotlin.services.iam.model.ListAttachedRolePoliciesRequest
import aws.sdk.kotlin.services.iam.model.ListAttachedRolePoliciesResponse
import aws.sdk.kotlin.services.iam.model.ListPoliciesRequest
import aws.sdk.kotlin.services.iam.model.ListPoliciesResponse
import aws.sdk.kotlin.services.iam.model.ListPolicyVersionsRequest
import aws.sdk.kotlin.services.iam.model.ListPolicyVersionsResponse
import aws.sdk.kotlin.services.iam.model.ListRolePoliciesRequest
import aws.sdk.kotlin.services.iam.model.ListRolePoliciesResponse
import aws.sdk.kotlin.services.iam.model.ListRolesRequest
import aws.sdk.kotlin.services.iam.model.ListRolesResponse
import aws.sdk.kotlin.services.iam.model.NoSuchEntityException
import aws.sdk.kotlin.services.iam.model.Policy
import aws.sdk.kotlin.services.iam.model.PolicyScopeType
import aws.sdk.kotlin.services.iam.model.PolicyVersion
import aws.sdk.kotlin.services.iam.model.Role
import aws.smithy.kotlin.runtime.time.Instant
import io.mockk.mockk

class IamClientStub(
  val delegate: IamClient = mockk<IamClient>()
) : IamClient by delegate {
  val roles = mutableListOf<RoleStub>()
  val policies = mutableListOf<PolicyStub>()

  data class RoleStub(
    val roleName: String,
    val attachedPolicies: MutableList<AttachedPolicyStub> = mutableListOf(),
    val inlinePolicies: MutableList<String> = mutableListOf(),
    val boundaryPolicyArn: String? = null
  )

  data class AttachedPolicyStub(
    val policyName: String,
    val policyArn: String
  )

  data class PolicyStub(
    val policyName: String,
    val policyArn: String,
    val policyVersions: MutableList<PolicyVersionStub> = mutableListOf()
  )

  data class PolicyVersionStub(
    val versionId: String,
    val isDefaultVersion: Boolean = false
  )

  private fun findRole(roleName: String?) =
      roles.find { it.roleName == roleName } ?: throw NoSuchEntityException { message = "Role $roleName not found"}

  private fun findPolicy(policyArn: String?) =
      policies.find { it.policyArn == policyArn } ?: throw NoSuchEntityException { message = "Policy $policyArn not found"}

  override suspend fun deleteRole(input: DeleteRoleRequest): DeleteRoleResponse {
    val role = findRole(input.roleName)
    if (role.inlinePolicies.isNotEmpty()) {
      throw IllegalStateException("Cannot delete role ${input.roleName} with inline policies")
    }
    if (role.attachedPolicies.isNotEmpty()) {
      throw IllegalStateException("Cannot delete role ${input.roleName} with attached policies")
    }
    roles.remove(role)
    return DeleteRoleResponse {}
  }

  override suspend fun detachRolePolicy(input: DetachRolePolicyRequest): DetachRolePolicyResponse {
    val role = findRole(input.roleName)
    val policy = role.attachedPolicies.find { it.policyArn == input.policyArn }
      ?: throw IllegalArgumentException("Policy ${input.policyArn} is not attached to role ${input.roleName}")
    role.attachedPolicies.remove(policy)
    return DetachRolePolicyResponse {}
  }

  override suspend fun deleteRolePolicy(input: DeleteRolePolicyRequest): DeleteRolePolicyResponse {
    val role = findRole(input.roleName)
    if (!role.inlinePolicies.remove(input.policyName)) {
      throw IllegalArgumentException("Policy ${input.policyName} is not an inline policy of role ${input.roleName}")
    }
    return DeleteRolePolicyResponse {}
  }

  override suspend fun listAttachedRolePolicies(input: ListAttachedRolePoliciesRequest): ListAttachedRolePoliciesResponse {
    val role = findRole(input.roleName)
    val attachedPolicies = role.attachedPolicies.map {
      AttachedPolicy {
        policyName = it.policyName
        policyArn = it.policyArn
      }
    }
    return ListAttachedRolePoliciesResponse {
      this.attachedPolicies = attachedPolicies
    }
  }

  override suspend fun listRolePolicies(input: ListRolePoliciesRequest): ListRolePoliciesResponse {
    val role = findRole(input.roleName)
    return ListRolePoliciesResponse {
      policyNames = role.inlinePolicies
    }
  }

  override suspend fun listRoles(input: ListRolesRequest): ListRolesResponse {
    val rolesSubset = roles.drop(input.marker?.toIntOrNull() ?: 0)
        .take(input.maxItems ?: roles.size)

    val nextMarker = if (rolesSubset.size < roles.size - (input.marker?.toIntOrNull() ?: 0)) {
      ((input.marker?.toIntOrNull() ?: 0) + rolesSubset.size).toString()
    } else null

    return ListRolesResponse {
      this.roles = rolesSubset.map { roleStub ->
        roleStub.toRole().copy { permissionsBoundary = null }
      }
      marker = nextMarker
      isTruncated = nextMarker != null
    }
  }

  override suspend fun getRole(input: GetRoleRequest): GetRoleResponse {
    return GetRoleResponse {
      this.role = findRole(input.roleName).toRole()
    }
  }

  override suspend fun createRole(input: CreateRoleRequest): CreateRoleResponse {
    if (roles.any { it.roleName == input.roleName }) {
      throw IllegalArgumentException("Role ${input.roleName} already exists")
    }
    val newRole = RoleStub(
      roleName = input.roleName!!,
      boundaryPolicyArn = input.permissionsBoundary
    )
    roles.add(newRole)
    return CreateRoleResponse {
      this.role = newRole.toRole()
    }
  }

  private fun RoleStub.toRole(): Role = Role {
    roleName = this@toRole.roleName
    arn = "arn:aws:iam::0000000:role/$roleName"
    permissionsBoundary = boundaryPolicyArn?.let { pb ->
      AttachedPermissionsBoundary {
        permissionsBoundaryArn = pb
      }
    }
    path = "/"
    createDate = Instant.now()
    roleId = arn
  }

  // Policy operations
  override suspend fun listPolicies(input: ListPoliciesRequest): ListPoliciesResponse {
    val filteredPolicies = if (input.scope == PolicyScopeType.Local) {
      policies
    } else {
      policies
    }

    val policiesSubset = filteredPolicies.drop(input.marker?.toIntOrNull() ?: 0)
        .take(input.maxItems ?: filteredPolicies.size)

    val nextMarker = if (policiesSubset.size < filteredPolicies.size - (input.marker?.toIntOrNull() ?: 0)) {
      ((input.marker?.toIntOrNull() ?: 0) + policiesSubset.size).toString()
    } else null

    return ListPoliciesResponse {
      this.policies = policiesSubset.map { policyStub ->
        policyStub.toPolicy()
      }
      marker = nextMarker
      isTruncated = nextMarker != null
    }
  }

  override suspend fun getPolicy(input: GetPolicyRequest): GetPolicyResponse {
    return GetPolicyResponse {
      this.policy = findPolicy(input.policyArn).toPolicy()
    }
  }

  override suspend fun deletePolicy(input: DeletePolicyRequest): DeletePolicyResponse {
    val policy = findPolicy(input.policyArn)
    if (policy.policyVersions.any { !it.isDefaultVersion }) {
      throw IllegalStateException("Cannot delete policy ${input.policyArn} with non-default versions")
    }
    policies.remove(policy)
    return DeletePolicyResponse {}
  }

  override suspend fun deletePolicyVersion(input: DeletePolicyVersionRequest): DeletePolicyVersionResponse {
    val policy = findPolicy(input.policyArn)
    val version = policy.policyVersions.find { it.versionId == input.versionId }
      ?: throw IllegalArgumentException("Version ${input.versionId} not found for policy ${input.policyArn}")
    if (version.isDefaultVersion) {
      throw IllegalArgumentException("Cannot delete default version ${input.versionId} of policy ${input.policyArn}")
    }
    policy.policyVersions.remove(version)
    return DeletePolicyVersionResponse {}
  }

  override suspend fun listPolicyVersions(input: ListPolicyVersionsRequest): ListPolicyVersionsResponse {
    val policy = findPolicy(input.policyArn)
    return ListPolicyVersionsResponse {
      this.versions = policy.policyVersions.map {
        PolicyVersion {
          versionId = it.versionId
          isDefaultVersion = it.isDefaultVersion
        }
      }
    }
  }

  private fun PolicyStub.toPolicy(): Policy = Policy {
    policyName = this@toPolicy.policyName
    arn = policyArn
    policyId = policyArn
    path = "/"
    createDate = Instant.now()
  }

  override fun close() {
    // No-op for stub
  }


}
