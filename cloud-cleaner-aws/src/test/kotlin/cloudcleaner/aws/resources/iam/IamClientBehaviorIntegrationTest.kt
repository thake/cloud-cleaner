package cloudcleaner.aws.resources.iam

import aws.sdk.kotlin.services.iam.IamClient
import aws.sdk.kotlin.services.iam.createRole
import cloudcleaner.aws.resources.LocalStack
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IamClientBehaviorIntegrationTest {
  val iamClient = IamClient {
    endpointUrl = LocalStack.localstackUrl
    region = "eu-central-1"
  }

  @Test fun `isRoleExisting should correctly detect not existing role`() = shouldCorrectlyDetectNotExistingRole(iamClient)

  @Test fun `isRoleExisting should correctly detect not existing role with stub`() = shouldCorrectlyDetectNotExistingRole(IamClientStub())

  private fun shouldCorrectlyDetectNotExistingRole(iamClient: IamClient) = runTest {
    val givenRoleName = Uuid.random().toString()
    iamClient.isRoleExisting(givenRoleName) shouldBe false
  }

  @Test fun `isRoleExisting should correctly detect existing role`() = shouldCorrectlyDetectExistingRole(iamClient)

  @Test fun `isRoleExisting should correctly detect existing role with stub`() = shouldCorrectlyDetectExistingRole(IamClientStub())

  private fun shouldCorrectlyDetectExistingRole(iamClient: IamClient) = runTest {
    val givenRoleName = Uuid.random().toString()
    iamClient.createRole {
      roleName = givenRoleName
      assumeRolePolicyDocument = "{}"
    }
    iamClient.isRoleExisting(givenRoleName) shouldBe true
  }

  @Test
  fun `getAttachedPolicies should return empty list for role without policies`() =
      getAttachedPoliciesShouldReturnEmptyListWithoutPolicy(iamClient)

  @Test
  fun `getAttachedPolicies should return empty list for role without policies with stub`() =
      getAttachedPoliciesShouldReturnEmptyListWithoutPolicy(IamClientStub())

  private fun getAttachedPoliciesShouldReturnEmptyListWithoutPolicy(iamClient: IamClient) = runTest {
    val givenRoleName = Uuid.random().toString()
    iamClient.createRole {
      roleName = givenRoleName
      assumeRolePolicyDocument = "{}"
    }
    val policies = iamClient.getAttachedPolicies(givenRoleName)

    policies.shouldBeEmpty()
  }

  @Test
  fun `getAttachedPolicies should return empty list for not existing role`() =
      getAttachedPoliciesShouldReturnEmptyIfRoleDoesNotExist(iamClient)

  @Test
  fun `getAttachedPolicies should return empty list for not existing role with stub`() =
      getAttachedPoliciesShouldReturnEmptyIfRoleDoesNotExist(IamClientStub())

  private fun getAttachedPoliciesShouldReturnEmptyIfRoleDoesNotExist(iamClient: IamClient) = runTest {
    val givenRoleName = Uuid.random().toString()
    val policies = iamClient.getAttachedPolicies(givenRoleName)

    policies.shouldBeEmpty()
  }

  @Test
  fun `getInlinePolicyNames should return empty list for role without policies`() =
      getInlinePolicyNamesShouldReturnEmptyListWithoutPolicy(iamClient)

  @Test
  fun `getInlinePolicyNames should return empty list for role without policies with stub`() =
      getInlinePolicyNamesShouldReturnEmptyListWithoutPolicy(IamClientStub())

  private fun getInlinePolicyNamesShouldReturnEmptyListWithoutPolicy(iamClient: IamClient) = runTest {
    val givenRoleName = Uuid.random().toString()
    iamClient.createRole {
      roleName = givenRoleName
      assumeRolePolicyDocument = "{}"
    }
    val policies = iamClient.getInlinePolicyNames(givenRoleName)

    policies.shouldBeEmpty()
  }

  @Test
  fun `getInlinePolicyNames should return empty list for not existing role`() =
      getInlinePolicyNamesShouldReturnEmptyIfRoleDoesNotExist(iamClient)

  @Test
  fun `getInlinePolicyNames should return empty list for not existing role with stub`() =
      getInlinePolicyNamesShouldReturnEmptyIfRoleDoesNotExist(IamClientStub())

  private fun getInlinePolicyNamesShouldReturnEmptyIfRoleDoesNotExist(iamClient: IamClient) = runTest {
    val givenRoleName = Uuid.random().toString()
    val policies = iamClient.getInlinePolicyNames(givenRoleName)

    policies.shouldBeEmpty()
  }

  // Policy tests
  @Test
  fun `isPolicyExisting should correctly detect not existing policy with stub`() = runTest {
    val stub = IamClientStub()
    val givenPolicyArn = "arn:aws:iam::123456789012:policy/${Uuid.random()}"
    stub.isPolicyExisting(givenPolicyArn) shouldBe iamClient.isPolicyExisting(givenPolicyArn)
  }

  @Test
  fun `getPolicyVersions should return empty list for not existing policy with stub`() = runTest {
    val stub = IamClientStub()
    val givenPolicyArn = "arn:aws:iam::123456789012:policy/${Uuid.random()}"
    stub.getPolicyVersions(givenPolicyArn).shouldBe(iamClient.getPolicyVersions(givenPolicyArn))
  }
}
