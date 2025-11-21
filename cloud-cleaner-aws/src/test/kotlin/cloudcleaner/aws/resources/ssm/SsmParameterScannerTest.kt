package cloudcleaner.aws.resources.ssm

import cloudcleaner.aws.resources.ACCOUNT_ID
import cloudcleaner.aws.resources.REGION
import cloudcleaner.aws.resources.ssm.SsmClientStub.ParameterStub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SsmParameterScannerTest {
  private val ssmClient = SsmClientStub()
  private val underTest = SsmParameterScanner(ssmClient, REGION)

  @Test
  fun `scan should return empty list when no parameters are present`() = runTest {
    // when
    val parameters = underTest.scan()

    // then
    parameters.toList().shouldBeEmpty()
  }

  @Test
  fun `scan should return list of paginated parameters`() = runTest {
    // given
    repeat(100) {
      ssmClient.parameters.add(
          ParameterStub(
              parameterName = "/my-app/config-$it",
              parameterArn = "arn:aws:ssm:$REGION:$ACCOUNT_ID:parameter/my-app/config-$it"
          )
      )
    }

    // when
    val actualFlow = underTest.scan()

    // then
    val actualParameters = actualFlow.toList()
    actualParameters.shouldHaveSize(100)
  }

  @Test
  fun `scan should return parameter details correctly`() = runTest {
    // given
    val parameter = ParameterStub(
        parameterName = "/my-app/database-url",
        parameterArn = "arn:aws:ssm:$REGION:$ACCOUNT_ID:parameter/my-app/database-url"
    )
    ssmClient.parameters.add(parameter)

    // when
    val actualFlow = underTest.scan()

    // then
    val actualParameters = actualFlow.toList()
    actualParameters.shouldHaveSize(1)
    val actualParameter = actualParameters.first()
    actualParameter.parameterName shouldBe SsmParameterName("/my-app/database-url", REGION)
    actualParameter.parameterArn.value shouldBe "arn:aws:ssm:$REGION:$ACCOUNT_ID:parameter/my-app/database-url"
    actualParameter.dependsOn.shouldBeEmpty()
  }

  @Test
  fun `scan should handle multiple parameters`() = runTest {
    // given
    ssmClient.parameters.add(
        ParameterStub(
            parameterName = "/my-app/parameter-1",
            parameterArn = "arn:aws:ssm:$REGION:$ACCOUNT_ID:parameter/my-app/parameter-1"
        )
    )
    ssmClient.parameters.add(
        ParameterStub(
            parameterName = "/my-app/parameter-2",
            parameterArn = "arn:aws:ssm:$REGION:$ACCOUNT_ID:parameter/my-app/parameter-2"
        )
    )

    // when
    val actualFlow = underTest.scan()

    // then
    val actualParameters = actualFlow.toList()
    actualParameters.shouldHaveSize(2)
  }
}

