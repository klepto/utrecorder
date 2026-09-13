package dev.klepto.utrecorder.common.config

/**
 * A reader of configuration values, backed by the system properties and by the environment.
 *
 * Use [DEFAULT] for the source the current process reads from.
 *
 * @property prefix The prefix every key is read under.
 */
class PropertySource(
    private val prefix: String = APPLICATION_PREFIX,
) {
    /**
     * Returns the value of the given [key], or `null` if neither the system properties nor the environment hold one.
     *
     * A dotted [key] reads from an underscored environment variable, since a dot is not a legal character in an
     * environment variable name on every platform.
     */
    operator fun get(key: String): String? =
        property("$prefix.$key")
            ?: environment("${prefix.uppercase()}_${key.uppercase().replace('.', '_')}")

    /**
     * Returns the value of the given [key] read by [mapper], or `null` if it holds none.
     */
    fun <T> get(
        key: String,
        mapper: (String) -> T,
    ): T? = get(key)?.let(mapper)

    /**
     * Returns the comma-separated values of the given [key], trimmed and without the empty ones, or `null` if it holds
     * none.
     */
    fun list(key: String): List<String>? =
        get(key)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    /**
     * Returns the value of the given system property [name], or `null` if it holds none. Unprefixed, for the
     * properties the JVM defines rather than this application.
     */
    fun property(name: String): String? = System.getProperty(name)?.takeIf { it.isNotEmpty() }

    /**
     * Returns the value of the given environment [variable], or `null` if it holds none. Unprefixed, for the variables
     * the operating system defines rather than this application.
     */
    fun environment(variable: String): String? = System.getenv(variable)?.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * The prefix keys of this application take.
         */
        const val APPLICATION_PREFIX = "utrecorder"

        /**
         * The source a configuration that names none reads from.
         */
        val DEFAULT = PropertySource()
    }
}
