package cloudcleaner.aws.resources.iam

import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.iam.IamClientStub.PolicyStub
import cloudcleaner.aws.resources.iam.IamClientStub.PolicyVersionStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class IamPolicyScannerTest {
    private val iamClient = IamClientStub()
    private val underTest = IamPolicyScanner(iamClient)

    @Test
    fun `scan should return empty list when no policies are present`() = runTest {
        val policies = underTest.scan()
        // then
        policies.toList().shouldBeEmpty()
    }

    @Test
    fun `scan should return list of paginated policies`() = runTest {
        repeat(100) {
            iamClient.policies.add(
                PolicyStub(
                    policyName = "policy-$it",
                    policyArn = "arn:aws:iam::$ACCOUNT_ID:policy/policy-$it",
                    policyVersions = mutableListOf(PolicyVersionStub("v1", isDefaultVersion = true))
                )
            )
        }
        // when
        val actualFlow = underTest.scan()
        // then
        val actualPolicies = actualFlow.toList()
        actualPolicies.shouldHaveSize(100)
    }

    @Test
    fun `scan should return policy details correctly`() = runTest {
        // given
        val policy = PolicyStub(
            policyName = "test-policy",
            policyArn = "arn:aws:iam::$ACCOUNT_ID:policy/test-policy",
            policyVersions = mutableListOf(PolicyVersionStub("v1", isDefaultVersion = true))
        )
        iamClient.policies.add(policy)

        // when
        val actualFlow = underTest.scan()

        // then
        val actualPolicies = actualFlow.toList()
        actualPolicies.shouldHaveSize(1)
        val actualPolicy = actualPolicies.first()
        actualPolicy.policyName shouldBe "test-policy"
        actualPolicy.policyArn.value shouldBe "arn:aws:iam::$ACCOUNT_ID:policy/test-policy"
        actualPolicy.dependsOn.shouldBeEmpty()
    }

    @Test
    fun `scan should handle multiple policies`() = runTest {
        // given
        iamClient.policies.add(
            PolicyStub(
                policyName = "policy-1",
                policyArn = "arn:aws:iam::$ACCOUNT_ID:policy/policy-1",
                policyVersions = mutableListOf(PolicyVersionStub("v1", isDefaultVersion = true))
            )
        )
        iamClient.policies.add(
            PolicyStub(
                policyName = "policy-2",
                policyArn = "arn:aws:iam::$ACCOUNT_ID:policy/policy-2",
                policyVersions = mutableListOf(PolicyVersionStub("v1", isDefaultVersion = true))
            )
        )

        // when
        val actualFlow = underTest.scan()

        // then
        val actualPolicies = actualFlow.toList()
        actualPolicies.shouldHaveSize(2)
    }
}

