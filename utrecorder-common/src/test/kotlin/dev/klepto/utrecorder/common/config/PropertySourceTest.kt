package dev.klepto.utrecorder.common.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Matching environment variables are set in `build.gradle.kts`.
 */
private const val TEST_PREFIX = "utrecorder_test"

private val testSource = PropertySource(TEST_PREFIX)

class PropertySourceTest :
    FunSpec({
        val property = SystemProperties()

        afterTest { property.clear() }

        test("a source built with no prefix reads under the application prefix") {
            property("${PropertySource.APPLICATION_PREFIX}.probe_key", "probe-value")

            PropertySource().get("probe_key") shouldBe "probe-value"
        }

        test("DEFAULT reads under the application prefix") {
            property("${PropertySource.APPLICATION_PREFIX}.probe_default", "probe-value")

            PropertySource.DEFAULT.get("probe_default") shouldBe "probe-value"
        }

        test("get reads the system property under the configured prefix") {
            property("$TEST_PREFIX.record_codec", "libopenh264")

            testSource.get("record_codec") shouldBe "libopenh264"
        }

        test("get returns null for a missing property") {
            testSource.get("record_codec").shouldBeNull()
        }

        test("get treats an empty property as missing") {
            property("$TEST_PREFIX.record_codec", "")

            testSource.get("record_codec").shouldBeNull()
        }

        test("get falls back to the uppercased environment variable") {
            testSource.get("env_codec") shouldBe "libx264"
        }

        test("get maps a dotted key to an underscored environment variable") {
            testSource.get("env.dotted.key") shouldBe "value-with-dots"
        }

        test("get prefers the system property over the environment variable") {
            property("$TEST_PREFIX.env_fps", "60")

            testSource.get("env_fps") shouldBe "60"
        }

        test("get treats an empty environment variable as missing") {
            testSource.get("env_empty").shouldBeNull()
        }

        test("get with a mapper maps the property value") {
            property("$TEST_PREFIX.record_fps", "30")

            testSource.get("record_fps") { it.toInt() } shouldBe 30
        }

        test("get with a mapper returns null for a missing property") {
            testSource.get("record_fps") { it.toInt() }.shouldBeNull()
        }

        test("get with a mapper does not invoke the mapper for a missing property") {
            var invoked = false

            testSource.get("record_fps") { invoked = true }.shouldBeNull()

            invoked shouldBe false
        }

        test("environment reads the given variable, unprefixed") {
            testSource.environment("UTRECORDER_TEST_ENV_CODEC") shouldBe "libx264"
        }

        test("environment returns null for a missing variable") {
            testSource.environment("UTRECORDER_TEST_NO_SUCH_VARIABLE").shouldBeNull()
        }

        test("environment treats an empty variable as missing") {
            testSource.environment("UTRECORDER_TEST_ENV_EMPTY").shouldBeNull()
        }
    })
