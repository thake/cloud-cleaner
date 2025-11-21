package cloudcleaner.aws.resources.ssm

import cloudcleaner.aws.resources.Arn
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.ssm.SsmClientStub.ParameterStub
import cloudcleaner.resources.StringId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SsmParameterDeleterTest {
  private val ssmClient = SsmClientStub()
  private val underTest = SsmParameterDeleter(ssmClient)

  @Test
  fun `delete should successfully delete a parameter`() = runTest {
    // given
    val parameter = SsmParameter(
        parameterName = SsmParameterName("/my-app/database-url", REGION),
        parameterArn = Arn("arn:aws:ssm:region:account-id:parameter/my-app/database-url")
    )
    ssmClient.parameters.add(
        ParameterStub(
            parameterName = parameter.parameterName.value
        )
    )

    // when
    underTest.delete(parameter)

    // then
    ssmClient.parameters.shouldHaveSize(0)
  }

  @Test
  fun `delete should throw exception when resource is not a SsmParameter`() = runTest {
    // given
    val invalidResource = object : cloudcleaner.resources.Resource {
      override val id = StringId("invalid")
      override val name = "invalid"
      override val type = "NotASsmParameter"
      override val properties = emptyMap<String, String>()
    }

    // when/then
    shouldThrow<IllegalArgumentException> {
      underTest.delete(invalidResource)
    }
  }

  @Test
  fun `delete should ignore not existing parameter`() = runTest {
    // given
    val parameter = SsmParameter(
        parameterName = SsmParameterName("/my-app/non-existent", REGION),
        parameterArn = Arn("arn:aws:ssm:region:account-id:parameter/my-app/non-existent")
    )

    // when/then - should not throw
    underTest.delete(parameter)
  }

  @Test
  fun `delete should delete multiple parameters`() = runTest {
    // given
    val parameter1 = SsmParameter(
        parameterName = SsmParameterName("/my-app/parameter-1", REGION),
        parameterArn = Arn("arn:aws:ssm:region:account-id:parameter/my-app/parameter-1")
    )
    val parameter2 = SsmParameter(
        parameterName = SsmParameterName("/my-app/parameter-2", REGION),
        parameterArn = Arn("arn:aws:ssm:region:account-id:parameter/my-app/parameter-2")
    )

    ssmClient.parameters.add(ParameterStub(parameter1.parameterName.value))
    ssmClient.parameters.add(ParameterStub(parameter2.parameterName.value))

    // when
    underTest.delete(parameter1)
    underTest.delete(parameter2)

    // then
    ssmClient.parameters.shouldHaveSize(0)
  }

  @Test
  fun `delete should only delete the specified parameter`() = runTest {
    // given
    val parameter1 = SsmParameter(
        parameterName = SsmParameterName("/my-app/parameter-1", REGION),
        parameterArn = Arn("arn:aws:ssm:region:account-id:parameter/my-app/parameter-1")
    )
    val parameter2 = SsmParameter(
        parameterName = SsmParameterName("/my-app/parameter-2", REGION),
        parameterArn = Arn("arn:aws:ssm:region:account-id:parameter/my-app/parameter-2")
    )

    ssmClient.parameters.add(ParameterStub(parameter1.parameterName.value))
    ssmClient.parameters.add(ParameterStub(parameter2.parameterName.value))

    // when
    underTest.delete(parameter1)

    // then
    ssmClient.parameters.shouldHaveSize(1)
    ssmClient.parameters[0].parameterName shouldBe parameter2.parameterName.value
  }
}

private infix fun String.shouldBe(expected: String) {
  if (this != expected) {
    throw AssertionError("Expected <$expected> but was <$this>")
  }
}

