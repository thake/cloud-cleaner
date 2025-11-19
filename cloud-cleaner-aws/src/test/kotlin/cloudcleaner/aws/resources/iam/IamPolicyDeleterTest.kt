package cloudcleaner.aws.resources.iam

import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.iam.IamClientStub.PolicyStub
import cloudcleaner.aws.resources.iam.IamClientStub.PolicyVersionStub
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class IamPolicyDeleterTest {
    private val iamClient = IamClientStub()
    private val underTest = IamPolicyDeleter(iamClient)

    @Test
    fun `delete should successfully delete a policy without versions`() = runTest {
        // given
        val policy = IamPolicy(
            policyName = "test-policy",
            policyArn = Arn("arn:aws:iam::123456789012:policy/test-policy")
        )
        iamClient.policies.add(PolicyStub(
            policyName = policy.policyName,
            policyArn = policy.policyArn.value,
            policyVersions = mutableListOf(PolicyVersionStub("v1", isDefaultVersion = true))
        ))

        // when
        underTest.delete(policy)

        // then
        iamClient.policies shouldHaveSize 0
    }

    @Test
    fun `delete should throw exception when resource is not an IamPolicy`() = runTest {
        // given
        val invalidResource = object : cloudcleaner.resources.Resource {
            override val id = cloudcleaner.resources.StringId("invalid")
            override val name = "invalid"
            override val type = "NotAnIamPolicy"
            override val properties = emptyMap<String, String>()
        }

        // when/then
        shouldThrow<IllegalArgumentException> {
            underTest.delete(invalidResource)
        }
    }

    @Test
    fun `delete should delete non-default policy versions before deleting policy`() = runTest {
        // given
        val policy = IamPolicy(
            policyName = "test-policy",
            policyArn = Arn("arn:aws:iam::123456789012:policy/test-policy")
        )
        val policyVersions = mutableListOf(
            PolicyVersionStub("v1", isDefaultVersion = true),
            PolicyVersionStub("v2", isDefaultVersion = false),
            PolicyVersionStub("v3", isDefaultVersion = false)
        )
        iamClient.policies.add(PolicyStub(
            policyName = policy.policyName,
            policyArn = policy.policyArn.value,
            policyVersions = policyVersions
        ))

        // when
        underTest.delete(policy)

        // then - verify all cleanup happened
        iamClient.policies shouldHaveSize 0
    }

    @Test
    fun `delete should ignore not existing policy`(): Unit = runTest {
        // given
        val policy = IamPolicy(
            policyName = "non-existent-policy",
            policyArn = Arn("arn:aws:iam::123456789012:policy/non-existent-policy")
        )

        // when/then - should not throw
        underTest.delete(policy)
    }
}

