package awspurge.resources

interface Id {
    fun isMatchedBy(string: String): Boolean
}
data class StringId(val value: String) : Id {
    override fun isMatchedBy(string: String) = this.value == string
    override fun toString() = value
}
