package dev.klepto.utrecorder.common.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class PropertySourceTest :
    FunSpec({
        fun source(
            properties: Map<String, String> = emptyMap(),
            environment: Map<String, String> = emptyMap(),
        ) = PropertySource("utrecorder_test", properties::get, environment::get)

        context("get") {
            test("reads the prefixed key from the properties") {
                source(properties = mapOf("utrecorder_test.record_codec" to "libopenh264"))["record_codec"] shouldBe
                    "libopenh264"
            }

            test("falls back to the upper-cased, underscored environment variable") {
                source(environment = mapOf("UTRECORDER_TEST_ENV_DOTTED_KEY" to "value-with-dots"))["env.dotted.key"] shouldBe
                    "value-with-dots"
            }

            test("prefers the properties over the environment") {
                val source =
                    source(
                        properties = mapOf("utrecorder_test.fps" to "60"),
                        environment = mapOf("UTRECORDER_TEST_FPS" to "24"),
                    )

                source["fps"] shouldBe "60"
            }

            test("returns null for a key neither map holds") {
                source()["record_codec"].shouldBeNull()
            }

            test("treats an empty property as missing") {
                source(properties = mapOf("utrecorder_test.record_codec" to ""))["record_codec"].shouldBeNull()
            }

            test("treats an empty environment variable as missing") {
                source(environment = mapOf("UTRECORDER_TEST_EMPTY" to ""))["empty"].shouldBeNull()
            }

            test("with a mapper maps the value") {
                source(properties = mapOf("utrecorder_test.record_fps" to "30")).get("record_fps") { it.toInt() } shouldBe 30
            }

            test("with a mapper does not invoke the mapper for a missing key") {
                var invoked = false

                source().get("record_fps") { invoked = true }.shouldBeNull()

                invoked shouldBe false
            }
        }

        context("duration") {
            test("reads 90s as 90 seconds") {
                source(properties = mapOf("utrecorder_test.retention" to "90s")).duration("retention") shouldBe 90.seconds
            }

            test("reads a missing key as null") {
                source().duration("retention").shouldBeNull()
            }

            test("throws IllegalArgumentException for a malformed value") {
                shouldThrow<IllegalArgumentException> {
                    source(properties = mapOf("utrecorder_test.retention" to "ninety")).duration("retention")
                }
            }
        }

        context("systemProperty") {
            test("reads an unprefixed name from the properties") {
                source(properties = mapOf("os.name" to "Linux")).systemProperty("os.name") shouldBe "Linux"
            }

            test("does not read the environment") {
                source(environment = mapOf("os.name" to "Linux")).systemProperty("os.name").shouldBeNull()
            }

            test("reads an empty value as null") {
                source(properties = mapOf("os.name" to "")).systemProperty("os.name").shouldBeNull()
            }
        }

        context("environmentVariable") {
            test("reads an unprefixed name from the environment") {
                source(environment = mapOf("LOCALAPPDATA" to "C:\\Local")).environmentVariable("LOCALAPPDATA") shouldBe
                    "C:\\Local"
            }

            test("does not read the properties") {
                source(properties = mapOf("LOCALAPPDATA" to "C:\\Local")).environmentVariable("LOCALAPPDATA").shouldBeNull()
            }

            test("reads an empty value as null") {
                source(environment = mapOf("LOCALAPPDATA" to "")).environmentVariable("LOCALAPPDATA").shouldBeNull()
            }
        }

        context("list") {
            test("splits a comma-separated value and trims each entry") {
                source(properties = mapOf("utrecorder_test.urls" to " http://one.test , http://two.test ")).list("urls") shouldBe
                    listOf("http://one.test", "http://two.test")
            }

            test("drops empty entries") {
                source(properties = mapOf("utrecorder_test.urls" to "http://one.test,,   ,http://two.test")).list("urls") shouldBe
                    listOf("http://one.test", "http://two.test")
            }

            test("returns null for a key nothing holds") {
                source().list("urls").shouldBeNull()
            }

            test("returns null for a key holding only separators and blanks") {
                source(properties = mapOf("utrecorder_test.urls" to " , ,, ")).list("urls").shouldBeNull()
            }
        }

        context("DEFAULT") {
            test("reads user.dir through systemProperty as the value the JVM holds") {
                PropertySource.DEFAULT.systemProperty("user.dir") shouldBe System.getProperty("user.dir")
            }
        }
    })
