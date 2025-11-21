package cloudcleaner.aws.resources.iam

import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.iam.IamClientStub.AttachedPolicyStub
import cloudcleaner.aws.resources.iam.IamClientStub.RoleStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class IamRoleScannerTest {
    private val iamClient = IamClientStub()
    private val underTest = IamRoleScanner(iamClient, REGION)

    @Test
    fun `scan should return empty list when no roles are present`() = runTest {
        val roles = underTest.scan()
        // then
        roles.toList().shouldBeEmpty()
    }

    @Test
    fun `scan should return list of paginated roles`() = runTest {
        repeat(100) {
            iamClient.roles.add(
                RoleStub(
                    roleName = "role-$it"
                )
            )
        }
        // when
        val actualFlow = underTest.scan()
        // then
        val actualRoles = actualFlow.toList()
        actualRoles.shouldHaveSize(100)
    }

    @Test
    fun `scan should return roles without attached policies`() = runTest {
        // given
        val role = RoleStub(
            roleName = "test-role"
        )
        iamClient.roles.add(role)

        // when
        val actualFlow = underTest.scan()

        // then
        val actualRoles = actualFlow.toList()
        actualRoles.shouldHaveSize(1)
        val actualRole = actualRoles.first()
        actualRole.name shouldBe "test-role"
        actualRole.dependsOn.shouldBeEmpty()
    }

    @Test
    fun `scan should return roles with attached policies as dependencies`() = runTest {
        // given
        val attachedPolicies = listOf(
            AttachedPolicyStub(
                policyName = "ReadOnlyAccess",
                policyArn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
            ),
            AttachedPolicyStub(
                policyName = "S3FullAccess",
                policyArn = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
            )
        )
        val role = RoleStub(
            roleName = "test-role",
            attachedPolicies = attachedPolicies.toMutableList()
        )
        iamClient.roles.add(role)

        // when
        val actualFlow = underTest.scan()

        // then
        val actualRoles = actualFlow.toList()
        actualRoles.shouldHaveSize(1)
        val actualRole = actualRoles.first()
        actualRole.dependsOn.shouldContainExactlyInAnyOrder(
            Arn("arn:aws:iam::aws:policy/ReadOnlyAccess"),
            Arn("arn:aws:iam::aws:policy/AmazonS3FullAccess")
        )
    }

    @Test
    fun `scan should handle roles with only inline policies`() = runTest {
        // given
        val role = RoleStub(
            roleName = "test-role",
            inlinePolicies = listOf("inline-policy-1", "inline-policy-2").toMutableList()
        )
        iamClient.roles.add(role)

        // when
        val actualFlow = underTest.scan()

        // then
        val actualRoles = actualFlow.toList()
        actualRoles.shouldHaveSize(1)
        val actualRole = actualRoles.first()
        actualRole.dependsOn.shouldBeEmpty() // inline policies are not dependencies
    }

    @Test
    fun `scan should handle roles with both attached and inline policies`() = runTest {
        // given
        val attachedPolicies = listOf(
            AttachedPolicyStub(
                policyName = "ReadOnlyAccess",
                policyArn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
            )
        )
        val role = RoleStub(
            roleName = "test-role",
            attachedPolicies = attachedPolicies.toMutableList(),
            inlinePolicies = listOf("inline-policy").toMutableList()
        )
        iamClient.roles.add(role)

        // when
        val actualFlow = underTest.scan()

        // then
        val actualRoles = actualFlow.toList()
        actualRoles.shouldHaveSize(1)
        val actualRole = actualRoles.first()
        actualRole.dependsOn.shouldContainExactlyInAnyOrder(
            Arn("arn:aws:iam::aws:policy/ReadOnlyAccess")
        )
    }

  @Test
  fun `scan should include permission boundary as dependency`() = runTest {
      // given
      val role = RoleStub(
          roleName = "test-role",
          boundaryPolicyArn = "arn:aws:iam::aws:policy/PowerUserAccess"
      )
      iamClient.roles.add(role)

      // when
      val actualFlow = underTest.scan()

      // then
      val actualRoles = actualFlow.toList()
      actualRoles.shouldHaveSize(1)
      val actualRole = actualRoles.first()
      actualRole.dependsOn.shouldContainExactlyInAnyOrder(
          Arn("arn:aws:iam::aws:policy/PowerUserAccess")
      )
  }
}
