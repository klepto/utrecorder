package dev.klepto.utrecorder.common.config

import kotlin.time.Duration

interface ConfigReader {
    operator fun get(key: String): String?

    fun <T> get(
        key: String,
        mapper: (String) -> T,
    ): T? = get(key)?.let(mapper)

    fun list(key: String): List<String>? =
        get(key)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    fun duration(key: String): Duration? = get(key, Duration::parse)
}

class SystemConfigReader(
    private val prefix: String = APPLICATION_PREFIX,
    private val properties: (String) -> String? = System::getProperty,
    private val environment: (String) -> String? = System::getenv,
) : ConfigReader {
    override operator fun get(key: String): String? =
        systemProperty("$prefix.$key")
            ?: environmentVariable("${prefix.uppercase()}_${key.uppercase().replace('.', '_')}")

    fun systemProperty(name: String): String? = properties(name)?.takeIf { it.isNotEmpty() }

    fun environmentVariable(variable: String): String? = environment(variable)?.takeIf { it.isNotEmpty() }

    companion object {
        const val APPLICATION_PREFIX = "utrecorder"
    }
}
