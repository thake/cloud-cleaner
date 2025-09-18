package cloudcleaner.aws.resources.iam

import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.iam.IamClientStub.AttachedPolicyStub
import cloudcleaner.aws.resources.iam.IamClientStub.RoleStub
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class IamRoleDeleterTest {
    private val iamClient = IamClientStub()
    private val underTest = IamRoleDeleter(iamClient)

    @Test
    fun `delete should successfully delete a role without policies`() = runTest {
        // given
        val role = IamRole(
            roleName = RoleName("test-role"),
            roleArn = Arn("arn:aws:iam::123456789012:role/test-role")
        )
        iamClient.roles.add(RoleStub(role.roleName.value, role.roleArn.value))

        // when
        underTest.delete(role)

        // then
        iamClient.roles shouldHaveSize 0
    }


    @Test
    fun `delete should throw exception when resource is not an IamRole`() = runTest {
        // given
        val invalidResource = object : cloudcleaner.resources.Resource {
            override val id = cloudcleaner.resources.StringId("invalid")
            override val name = "invalid"
            override val type = "NotAnIamRole"
            override val properties = emptyMap<String, String>()
        }

        // when/then
        shouldThrow<IllegalArgumentException> {
            underTest.delete(invalidResource)
        }
    }

    @Test
    fun `delete should handle role deletion in correct order`() = runTest {
        // given - role with both types of policies
        val attachedPolicies = mutableListOf(
            AttachedPolicyStub("ReadOnlyAccess", "arn:aws:iam::aws:policy/ReadOnlyAccess")
        )
        val inlinePolicies = mutableListOf("inline-policy")
        val role = IamRole(
            roleName = RoleName("test-role"),
            roleArn = Arn("arn:aws:iam::123456789012:role/test-role")
        )
        iamClient.roles.add(
            RoleStub(
                roleName = role.roleName.value,
                roleArn = role.roleArn.value,
                attachedPolicies = attachedPolicies,
                inlinePolicies = inlinePolicies
            )
        )

        // when
        underTest.delete(role)

        // then - verify all cleanup happened
        iamClient.roles shouldHaveSize 0
    }

  @Test
  fun `delete should ignore not existing role`(): Unit = runTest {
    // given
    val role = IamRole(
      roleName = RoleName("non-existent-role"),
      roleArn = Arn("arn:aws:iam::123456789012:role/non-existent-role")
    )

    // when/then - should not throw
    underTest.delete(role)
  }
}
