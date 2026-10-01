package dev.klepto.utrecorder.common

import kotlin.time.Duration

/**
 * Source of configuration values, each looked up by a dotted key such as `home` or `record.fps`.
 *
 * Use [SystemConfigReader] to read values from system properties and environment variables, or [EMPTY] for a reader
 * that configures no key and holds no system property or environment variable.
 */
fun interface ConfigReader {
    /**
     * Returns the value configured for [key], or `null` if none is configured.
     */
    operator fun get(key: String): String?

    /**
     * Returns the system property [name], without any prefix, or `null` if this reader holds none of that name.
     */
    fun systemProperty(name: String): String? = null

    /**
     * Returns the environment variable [name], without any prefix, or `null` if this reader holds none of that name.
     */
    fun environmentVariable(name: String): String? = null

    /**
     * Returns the value configured for [key], converted by [mapper]. If none is configured, returns `null` instead.
     */
    fun <T> get(
        key: String,
        mapper: (String) -> T,
    ): T? = get(key)?.let(mapper)

    /**
     * Returns the value configured for [key]. If none is configured, returns [default] instead.
     */
    fun get(
        key: String,
        default: String,
    ): String = get(key) ?: default

    /**
     * Returns the value configured for [key], converted by [mapper]. If none is configured, returns [default] instead.
     */
    fun <T> get(
        key: String,
        default: T,
        mapper: (String) -> T,
    ): T = get(key, mapper) ?: default

    /**
     * Returns the comma-separated items configured for [key], trimmed and without empty items. If none is configured,
     * or no item is left, returns `null` instead.
     */
    fun list(key: String): List<String>? =
        get(key)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    /**
     * Returns the comma-separated items configured for [key], trimmed and without empty items. If none is configured,
     * or no item is left, returns [default] instead.
     */
    fun list(
        key: String,
        default: List<String>,
    ): List<String> = list(key) ?: default

    /**
     * Returns the [Duration] configured for [key], in the format [Duration.parse] reads. If none is configured, returns
     * `null` instead.
     */
    fun duration(key: String): Duration? = get(key, Duration::parse)

    /**
     * Returns the [Duration] configured for [key], in the format [Duration.parse] reads. If none is configured, returns
     * [default] instead.
     */
    fun duration(
        key: String,
        default: Duration,
    ): Duration = duration(key) ?: default

    companion object {
        /**
         * The reader that configures no key, so every configuration built from it keeps its literal defaults.
         */
        val EMPTY: ConfigReader = ConfigReader { null }
    }
}

/**
 * [ConfigReader] that reads a key from system properties first, then from environment variables.
 *
 * The key `home` read under the prefix `utrecorder` is the system property `utrecorder.home`, or else the environment
 * variable `UTRECORDER_HOME`. A dot in the key becomes an underscore in the environment variable. An empty value counts
 * as not configured.
 *
 * @property prefix the prefix every key is read under.
 * @property properties the lookup of system properties by name.
 * @property environment the lookup of environment variables by name.
 */
class SystemConfigReader(
    private val prefix: String = APPLICATION_PREFIX,
    private val properties: (String) -> String? = System::getProperty,
    private val environment: (String) -> String? = System::getenv,
) : ConfigReader {
    override operator fun get(key: String): String? =
        systemProperty("$prefix.$key")
            ?: environmentVariable("${prefix.uppercase()}_${key.uppercase().replace('.', '_')}")

    override fun systemProperty(name: String): String? = properties(name)?.takeIf { it.isNotEmpty() }

    override fun environmentVariable(name: String): String? = environment(name)?.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * The prefix of the application's own keys.
         */
        const val APPLICATION_PREFIX = "utrecorder"
    }
}
