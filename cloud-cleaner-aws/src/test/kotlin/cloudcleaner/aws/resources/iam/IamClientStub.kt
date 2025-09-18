package cloudcleaner.aws.resources.iam

import aws.sdk.kotlin.services.iam.IamClient
import aws.sdk.kotlin.services.iam.model.AttachedPermissionsBoundary
import aws.sdk.kotlin.services.iam.model.AttachedPolicy
import aws.sdk.kotlin.services.iam.model.CreateRoleRequest
import aws.sdk.kotlin.services.iam.model.CreateRoleResponse
import aws.sdk.kotlin.services.iam.model.DeleteRolePolicyRequest
import aws.sdk.kotlin.services.iam.model.DeleteRolePolicyResponse
import aws.sdk.kotlin.services.iam.model.DeleteRoleRequest
import aws.sdk.kotlin.services.iam.model.DeleteRoleResponse
import aws.sdk.kotlin.services.iam.model.DetachRolePolicyRequest
import aws.sdk.kotlin.services.iam.model.DetachRolePolicyResponse
import aws.sdk.kotlin.services.iam.model.GetRoleRequest
import aws.sdk.kotlin.services.iam.model.GetRoleResponse
import aws.sdk.kotlin.services.iam.model.ListAttachedRolePoliciesRequest
import aws.sdk.kotlin.services.iam.model.ListAttachedRolePoliciesResponse
import aws.sdk.kotlin.services.iam.model.ListRolePoliciesRequest
import aws.sdk.kotlin.services.iam.model.ListRolePoliciesResponse
import aws.sdk.kotlin.services.iam.model.ListRolesRequest
import aws.sdk.kotlin.services.iam.model.ListRolesResponse
import aws.sdk.kotlin.services.iam.model.NoSuchEntityException
import aws.sdk.kotlin.services.iam.model.Role
import aws.smithy.kotlin.runtime.time.Instant
import io.mockk.mockk

class IamClientStub(
  val delegate: IamClient = mockk<IamClient>()
) : IamClient by delegate {
  val roles = mutableListOf<RoleStub>()

  data class RoleStub(
    val roleName: String,
    val roleArn: String,
    val attachedPolicies: MutableList<AttachedPolicyStub> = mutableListOf(),
    val inlinePolicies: MutableList<String> = mutableListOf(),
    val boundaryPolicyArn: String? = null
  )

  data class AttachedPolicyStub(
    val policyName: String,
    val policyArn: String
  )

  private fun findRole(roleName: String?) =
      roles.find { it.roleName == roleName } ?: throw NoSuchEntityException { message = "Role $roleName not found"}

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
        roleStub.toRole()
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
      roleArn = "arn:aws:iam::123456789012:role/${input.roleName}",
      boundaryPolicyArn = input.permissionsBoundary
    )
    roles.add(newRole)
    return CreateRoleResponse {
      this.role = newRole.toRole()
    }
  }

  private fun RoleStub.toRole(): Role = Role {
    roleName = this@toRole.roleName
    arn = roleArn
    permissionsBoundary = boundaryPolicyArn?.let { pb ->
      AttachedPermissionsBoundary {
        permissionsBoundaryArn = pb
      }
    }
    path = "/"
    createDate = Instant.now()
    roleId = roleArn
  }

  override fun close() {
    // No-op for stub
  }


}
