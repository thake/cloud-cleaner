package cloudcleaner.aws.config

import cloudcleaner.config.ContainsFilter
import cloudcleaner.config.Filter
import cloudcleaner.config.RegexFilter
import cloudcleaner.config.TypeFilter
import cloudcleaner.config.ValueFilter
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlList
import com.charleskorn.kaml.yamlMap
import com.charleskorn.kaml.yamlScalar
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

class ConfigReader {
  fun readConfig(file: Path): Config {
    val fileContent = SystemFileSystem.source(file).use { source -> source.buffered().readString() }
    return readConfig(fileContent)
  }

  fun readConfig(config: String): Config {
    val yamlNode = Yaml.default.parseToYamlNode(config)
    val regions = yamlNode.yamlMap.get<YamlList>("regions")?.items?.map { it.yamlScalar.content } ?: emptyList()
    val filterDefinitions =
        yamlNode.yamlMap
            .get<YamlMap>("filterDefinitions")
            ?.entries
            ?.map { filterDefinitionNode ->
              val filterName = filterDefinitionNode.key.content
              val filters = filterDefinitionNode.value.yamlList.items.map { typeFilterNode -> parseFilter(typeFilterNode) }
              filterName to filters
            }
            ?.toMap() ?: emptyMap()
    val accounts =
        yamlNode.yamlMap.get<YamlMap>("accounts")?.entries?.map { accountNode ->
          val accountId = accountNode.key.content
          val accountConfig = accountNode.value as? YamlMap ?: return@map Config.AccountConfig(accountId, null, emptyList(), emptyList())
          val excludeFilters =
              accountConfig
                  .get<YamlList>("excludeFilters")
                  ?.items
                  ?.map { parseTypeFiltersOrResolveReference(it, filterDefinitions) }
                  ?.flatten() ?: emptyList()
          val includeFilters =
              accountConfig
                  .get<YamlList>("includeFilters")
                  ?.items
                  ?.map { parseTypeFiltersOrResolveReference(it, filterDefinitions) }
                  ?.flatten() ?: emptyList()

          val role = accountConfig.get<YamlScalar>("assumeRole")?.content
          Config.AccountConfig(accountId, role, excludeFilters, includeFilters)
        }
    val resourceTypes =
        yamlNode.yamlMap.get<YamlMap>("resourceTypes")?.let { resourceTypesNode ->
          val includes = resourceTypesNode.get<YamlList>("includes")?.items?.map { it.yamlScalar.content } ?: emptyList()
          val excludes = resourceTypesNode.get<YamlList>("excludes")?.items?.map { it.yamlScalar.content } ?: emptyList()
          Config.ResourceTypes(includes, excludes)
        } ?: Config.ResourceTypes(emptyList(), emptyList())
    require(accounts != null) { "No accounts found in config" }
    require(regions.isNotEmpty()) { "No regions found in config" }
    return Config(regions, accounts, resourceTypes)
  }

  private fun parseTypeFiltersOrResolveReference(it: YamlNode, filterDefinitions: Map<String, List<Filter>>) =
      if (it is YamlScalar) {
        filterDefinitions[it.content] ?: throw IllegalArgumentException("Filter definition ${it.content} not found")
      } else {
        listOf(parseFilter(it))
      }

  private fun parseFilter(filterNode: YamlNode): Filter =
      when {
        filterNode.yamlMap.getKey("regex") != null -> {
          val regex = filterNode.yamlMap.get<YamlScalar>("regex")!!.content
          val property = filterNode.yamlMap.get<YamlScalar>("property")?.content
          RegexFilter(regex, property)
        }

        filterNode.yamlMap.getKey("value") != null -> {
          val value = filterNode.yamlMap.get<YamlScalar>("value")!!.content
          val property = filterNode.yamlMap.get<YamlScalar>("property")?.content
          ValueFilter(value, property)
        }

        filterNode.yamlMap.getKey("contains") != null -> {
          val contains = filterNode.yamlMap.get<YamlScalar>("contains")!!.content
          val property = filterNode.yamlMap.get<YamlScalar>("property")?.content
          ContainsFilter(contains, property)
        }
        filterNode.yamlMap.entries.size == 1 && filterNode.yamlMap.entries.values.first() is YamlList -> {
          val type = filterNode.yamlMap.entries.keys.first().content
          val filters =
              filterNode.yamlMap.entries.values.first().yamlList.items.map { childFilterNode -> parseFilter(childFilterNode) }
          TypeFilter(type, filters)
        }

        else -> throw IllegalArgumentException("Unknown filter type")
      }
}
