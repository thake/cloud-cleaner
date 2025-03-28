package awspurge.config

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ConfigReaderTest {
    @Test
    fun `readConfig should successfully parse valid config`() {
        // language=yaml
        val validConfig = """
            regions:
            - us-east-1
            - eu-central-1
            accounts:
              000000000002:
                assumeRole: admin
                excludeFilters:
                  - foo
              000000000001:
                assumeRole: anotherAdmin
                excludeFilters:
                  - value: some-id
                  - CloudFormationStack: 
                      - value: "blub"
                  - SsmParameter:
                      - contains: "*yeah*"
                  - foo
              000000000003:
                assumeRole: user
            resourceTypes:
              includes:
                - CloudFormationStack
                - SSMParameter
            filterDefinitions:
              foo:
                - CloudFormationStack:
                   - regex: "foo"
                   - regex: "bar"
        """.trimIndent()
        val configReader = ConfigReader()
        val config = configReader.readConfig(validConfig)
        // then
        config.regions.shouldBe(listOf("us-east-1", "eu-central-1"))
        val fooFilterDefinition =
            TypeFilter("CloudFormationStack", listOf(RegexFilter("foo"), RegexFilter("bar")))
        config.accounts.shouldBe(
            listOf(
                Config.AccountConfig(
                    accountId = "000000000002",
                    assumeRole = "admin",
                    excludeFilters = listOf(fooFilterDefinition),
                ),
                Config.AccountConfig(
                    accountId = "000000000001",
                    assumeRole = "anotherAdmin",
                    excludeFilters = listOf(
                        ValueFilter("some-id"),
                        TypeFilter("CloudFormationStack", listOf(ValueFilter("blub"))),
                        TypeFilter("SsmParameter", listOf(ContainsFilter("*yeah*"))),
                        fooFilterDefinition
                    )
                ),
                Config.AccountConfig(
                    accountId = "000000000003",
                    assumeRole = "user",
                    excludeFilters = emptyList()
                )
            )
        )
        config.resourceTypes.shouldBe(
            Config.ResourceTypes(
                includes = listOf("CloudFormationStack", "SSMParameter"),
                excludes = emptyList()
            )
        )
    }
}