package cloudcleaner.aws.config

import cloudcleaner.config.ContainsFilter
import cloudcleaner.config.RegexFilter
import cloudcleaner.config.TypeFilter
import cloudcleaner.config.ValueFilter
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ConfigReaderTest {
  @Test
  fun `readConfig should successfully parse valid config`() {
    // language=yaml
    val validConfig =
        """
        regions:
        - us-east-1
        - eu-central-1
        accounts:
          000000000002:
            assumeRole: admin
            excludeFilters:
              - foo
            includeFilters:
              - foo
          000000000001:
            assumeRole: anotherAdmin
            includeFilters:
              - value: some-id
              - CloudFormationStack: 
                  - value: "blub"
              - SsmParameter:
                  - contains: "*yeah*"
              - foo
            excludeFilters:
              - value: some-id
              - CloudFormationStack: 
                  - value: "blub"
              - SsmParameter:
                  - contains: "*yeah*"
              - foo
          000000000003:
        resourceTypes:
          includes:
            - CloudFormationStack
            - SSMParameter
        filterDefinitions:
          foo:
            - CloudFormationStack:
               - regex: "foo"
               - regex: "bar"
        """
            .trimIndent()
    val configReader = ConfigReader()
    val config = configReader.readConfig(validConfig)
    // then
    config.regions.shouldBe(listOf("us-east-1", "eu-central-1"))
    val fooFilterDefinition = TypeFilter("CloudFormationStack", listOf(RegexFilter("foo"), RegexFilter("bar")))
    val filters =
        listOf(
            ValueFilter("some-id"),
            TypeFilter("CloudFormationStack", listOf(ValueFilter("blub"))),
            TypeFilter("SsmParameter", listOf(ContainsFilter("*yeah*"))),
            fooFilterDefinition)
    config.accounts.shouldBe(
        listOf(
            Config.AccountConfig(
                accountId = "000000000002",
                assumeRole = "admin",
                profile = null,
                excludeFilters = listOf(fooFilterDefinition),
                includeFilters = listOf(fooFilterDefinition),
            ),
            Config.AccountConfig(
                accountId = "000000000001", assumeRole = "anotherAdmin", profile = null, excludeFilters = filters, includeFilters = filters),
            Config.AccountConfig(
                accountId = "000000000003", assumeRole = null, profile = null, excludeFilters = emptyList(), includeFilters = emptyList())),
    )
    config.resourceTypes.shouldBe(Config.ResourceTypes(includes = listOf("CloudFormationStack", "SSMParameter"), excludes = emptyList()))
  }

  @Test
  fun `readConfig should support profile configuration for SSO`() {
    // language=yaml
    val validConfig =
        """
        regions:
        - us-east-1
        accounts:
          123456789012:
            profile: my-sso-profile
          987654321098:
            profile: another-profile
            assumeRole: admin
          111111111111:
            assumeRole: admin
        resourceTypes:
          includes:
            - CloudFormationStack
        """
            .trimIndent()
    val configReader = ConfigReader()
    val config = configReader.readConfig(validConfig)
    // then
    config.regions.shouldBe(listOf("us-east-1"))
    config.accounts.shouldBe(
        listOf(
            Config.AccountConfig(
                accountId = "123456789012",
                assumeRole = null,
                profile = "my-sso-profile",
                excludeFilters = emptyList(),
                includeFilters = emptyList(),
            ),
            Config.AccountConfig(
                accountId = "987654321098",
                assumeRole = "admin",
                profile = "another-profile",
                excludeFilters = emptyList(),
                includeFilters = emptyList(),
            ),
            Config.AccountConfig(
                accountId = "111111111111",
                assumeRole = "admin",
                profile = null,
                excludeFilters = emptyList(),
                includeFilters = emptyList(),
            )
        ),
    )
    config.resourceTypes.shouldBe(Config.ResourceTypes(includes = listOf("CloudFormationStack"), excludes = emptyList()))
  }
}
