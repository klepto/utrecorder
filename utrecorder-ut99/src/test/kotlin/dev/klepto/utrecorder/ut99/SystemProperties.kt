package dev.klepto.utrecorder.ut99

/**
 * Sets system properties for the duration of one test, remembering every key set so a spec's `afterTest` block can
 * clear them in one call rather than a hand-rolled `try`/`finally` per test. A missed `clearProperty` leaks into
 * every later spec run in the same JVM.
 */
class SystemProperties {
    private val keys = mutableListOf<String>()

    operator fun invoke(
        key: String,
        value: String,
    ) {
        keys += key
        System.setProperty(key, value)
    }

    /**
     * Clears every property set through this fixture. Call from `afterTest`.
     */
    fun clear() {
        keys.forEach(System::clearProperty)
        keys.clear()
    }
}
