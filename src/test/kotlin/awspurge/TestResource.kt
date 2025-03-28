package awspurge

import awspurge.resources.Id
import awspurge.resources.Resource
import awspurge.resources.StringId

data class TestResource(
    override val id: Id,
    override val name: String = id.toString(),
    override val properties: Map<String, String> = emptyMap(),
    override val type: String = "Test",
    override val dependsOn: Set<StringId> = emptySet(),
    override val contains: Set<StringId> = emptySet(),

    ) : Resource {

    constructor(id: String,
                name: String = id,
                dependsOn: Set<String> = emptySet(),
                contains: Set<String> = emptySet()) : this(
        id = StringId(id),
        name = name,
        dependsOn = dependsOn.map { StringId(it) }.toSet(),
        contains = contains.map { StringId(it) }.toSet()
    )
}