package cloudcleaner.resources.aws

import aws.sdk.kotlin.services.cloudformation.CloudFormationClient
import aws.sdk.kotlin.services.cloudformation.listImports
import aws.sdk.kotlin.services.cloudformation.model.CloudFormationException
import aws.sdk.kotlin.services.cloudformation.model.Export
import aws.sdk.kotlin.services.cloudformation.model.ListExportsResponse
import aws.sdk.kotlin.services.cloudformation.model.ListImportsResponse
import io.kotest.matchers.maps.shouldContainAll
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CloudFormationStackStateKtTest {

    @Test
    fun `outputDependencyMap should correctly detect dependencies based on output values`() = runTest {
        val client = mockk<CloudFormationClient>()
        val output1Name = "output1"
        val output2Name = "output2"
        val output3Name = "output3"

        coEvery { client.listExports() }.returns(ListExportsResponse {
            val stackId =
                "arn:aws:cloudformation:eu-central-1:542247234417:stack/stack-with-output/70b77990-ff00-11ef-a6a6-06d6ed382a0d"
            exports = listOf(
                Export {
                    name = output1Name
                    value = "dummy"
                    exportingStackId = stackId
                },
                Export {
                    name = output2Name
                    value = "dummy"
                    exportingStackId = stackId
                },
                Export {
                    name = output3Name
                    value = "dummy"
                    exportingStackId = "arn:aws:cloudformation:eu-central-1:542247234417:stack/second-stack-with-output/70b77990-ff00-11ef-a6a6-06d6ed382a0d"
                },
                Export{
                    name = "unusedExport"
                    value = "dummy"
                    exportingStackId = stackId
                })
        })
        coEvery { client.listImports(any()) }.throws(CloudFormationException("is not imported by any stack."))
        coEvery { client.listImports {
            exportName = output1Name
        } }.returns(ListImportsResponse {
            imports = listOf("stack-using-output-1")
        })
        coEvery { client.listImports {
            exportName = output2Name
        } }.returns(ListImportsResponse {
            imports = listOf("stack-using-output-2", "stack-using-output-1")
        })
        coEvery { client.listImports {
            exportName = output3Name
        } }.returns(ListImportsResponse {
            imports = listOf("stack-using-output-2", "stack-using-output-1")
        })
        // when
        val result = client.outputDependencyMap()

        // then
        result shouldContainAll mapOf(
            StackName("stack-using-output-1") to setOf(StackName("stack-with-output"), StackName("second-stack-with-output")),
            StackName("stack-using-output-2") to setOf(StackName("stack-with-output"), StackName("second-stack-with-output"))
        )
    }
}