package dev.klepto.utrecorder.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ConfigReaderTest :
    FunSpec({
        fun reader(
            properties: Map<String, String> = emptyMap(),
            environment: Map<String, String> = emptyMap(),
        ) = SystemConfigReader("utrecorder_test", properties::get, environment::get)

        context("get") {
            test("reads the prefixed key from the properties") {
                reader(properties = mapOf("utrecorder_test.record_codec" to "libopenh264"))["record_codec"] shouldBe
                    "libopenh264"
            }

            test("falls back to the upper-cased, underscored environment variable") {
                reader(environment = mapOf("UTRECORDER_TEST_ENV_DOTTED_KEY" to "value-with-dots"))["env.dotted.key"] shouldBe
                    "value-with-dots"
            }

            test("prefers the properties over the environment") {
                val reader =
                    reader(
                        properties = mapOf("utrecorder_test.fps" to "60"),
                        environment = mapOf("UTRECORDER_TEST_FPS" to "24"),
                    )

                reader["fps"] shouldBe "60"
            }

            test("returns null for a missing key") {
                reader()["record_codec"].shouldBeNull()
            }

            test("treats an empty property as missing") {
                reader(properties = mapOf("utrecorder_test.record_codec" to ""))["record_codec"].shouldBeNull()
            }

            test("treats an empty environment variable as missing") {
                reader(environment = mapOf("UTRECORDER_TEST_EMPTY" to ""))["empty"].shouldBeNull()
            }

            test("with a mapper maps the value") {
                reader(properties = mapOf("utrecorder_test.record_fps" to "30")).get("record_fps") { it.toInt() } shouldBe 30
            }

            test("with a mapper does not invoke the mapper for a missing key") {
                var invoked = false

                reader().get("record_fps") { invoked = true }.shouldBeNull()

                invoked shouldBe false
            }

            test("with a default returns the configured value") {
                reader(properties = mapOf("utrecorder_test.record_codec" to "libx264")).get("record_codec", "libsvtav1") shouldBe "libx264"
            }

            test("with a default returns the default for a missing key") {
                reader().get("record_codec", "libsvtav1") shouldBe "libsvtav1"
            }

            test("with a default and a mapper maps the configured value") {
                reader(properties = mapOf("utrecorder_test.record_fps" to "30")).get("record_fps", 60, String::toInt) shouldBe 30
            }

            test("with a default and a mapper returns the default for a missing key") {
                reader().get("record_fps", 60, String::toInt) shouldBe 60
            }
        }

        context("duration") {
            test("reads 90s as 90 seconds") {
                reader(properties = mapOf("utrecorder_test.retention" to "90s")).duration("retention") shouldBe 90.seconds
            }

            test("returns null for a missing key") {
                reader().duration("retention").shouldBeNull()
            }

            test("with a default returns the default for a missing key") {
                reader().duration("retention", 30.seconds) shouldBe 30.seconds
            }

            test("throws IllegalArgumentException for a malformed value") {
                shouldThrow<IllegalArgumentException> {
                    reader(properties = mapOf("utrecorder_test.retention" to "ninety")).duration("retention")
                }
            }
        }

        context("systemProperty") {
            test("reads an unprefixed name from the properties") {
                reader(properties = mapOf("os.name" to "Linux")).systemProperty("os.name") shouldBe "Linux"
            }

            test("does not read the environment") {
                reader(environment = mapOf("os.name" to "Linux")).systemProperty("os.name").shouldBeNull()
            }

            test("reads an empty value as null") {
                reader(properties = mapOf("os.name" to "")).systemProperty("os.name").shouldBeNull()
            }
        }

        context("environmentVariable") {
            test("reads an unprefixed name from the environment") {
                reader(environment = mapOf("LOCALAPPDATA" to "C:\\Local")).environmentVariable("LOCALAPPDATA") shouldBe
                    "C:\\Local"
            }

            test("does not read the properties") {
                reader(properties = mapOf("LOCALAPPDATA" to "C:\\Local")).environmentVariable("LOCALAPPDATA").shouldBeNull()
            }

            test("reads an empty value as null") {
                reader(environment = mapOf("LOCALAPPDATA" to "")).environmentVariable("LOCALAPPDATA").shouldBeNull()
            }
        }

        context("list") {
            test("splits a comma-separated value and trims each entry") {
                reader(properties = mapOf("utrecorder_test.urls" to " http://one.test , http://two.test ")).list("urls") shouldBe
                    listOf("http://one.test", "http://two.test")
            }

            test("drops empty entries") {
                reader(properties = mapOf("utrecorder_test.urls" to "http://one.test,,   ,http://two.test")).list("urls") shouldBe
                    listOf("http://one.test", "http://two.test")
            }

            test("returns null for a missing key") {
                reader().list("urls").shouldBeNull()
            }

            test("returns null for a key holding only separators and blanks") {
                reader(properties = mapOf("utrecorder_test.urls" to " , ,, ")).list("urls").shouldBeNull()
            }

            test("with a default returns the default for a missing key") {
                reader().list("urls", listOf("http://default.test")) shouldBe listOf("http://default.test")
            }
        }

        context("EMPTY") {
            test("configures no key") {
                ConfigReader.EMPTY["home"].shouldBeNull()
            }

            test("holds no system property") {
                ConfigReader.EMPTY.systemProperty("user.home").shouldBeNull()
            }

            test("holds no environment variable") {
                ConfigReader.EMPTY.environmentVariable("LOCALAPPDATA").shouldBeNull()
            }
        }

        context("default lookups") {
            test("reads system properties of the JVM") {
                SystemConfigReader().systemProperty("user.dir") shouldBe System.getProperty("user.dir")
            }
        }
    })
