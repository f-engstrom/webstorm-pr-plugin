package se.personal.adoprreview.api

/** ADO stores @mentions in Markdown as `@<identity GUID>`; these helpers swap them for names. */
object Mentions {
    private val PATTERN = Regex("""@<([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})>""")

    /** Lower-cased identity IDs mentioned in [text]. */
    fun ids(text: String?): Set<String> =
        if (text == null) emptySet() else PATTERN.findAll(text).mapTo(mutableSetOf()) { it.groupValues[1].lowercase() }

    /** Replaces each mention with a bold `@Name`, using [names] keyed by lower-cased ID. */
    fun replace(text: String, names: Map<String, String>): String =
        PATTERN.replace(text) { match ->
            val name = names[match.groupValues[1].lowercase()] ?: "unknown user"
            "**@${name.replace("*", "\\*")}**"
        }
}
