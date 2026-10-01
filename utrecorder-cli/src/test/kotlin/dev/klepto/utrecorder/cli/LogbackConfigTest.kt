package dev.klepto.utrecorder.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class LogbackConfigTest :
    FunSpec({
        fun targetOf(appender: Element): String? = appender.getElementsByTagName("target").item(0)?.textContent

        test("every console appender writes to standard error, so standard out carries only the mp4 path") {
            val config =
                checkNotNull(javaClass.classLoader.getResourceAsStream("logback.xml")) {
                    "No logback.xml on the classpath"
                }
            val document = config.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
            val appenders = document.getElementsByTagName("appender")
            val targets =
                (0 until appenders.length)
                    .map { appenders.item(it) as Element }
                    .filter { it.getAttribute("class") == "ch.qos.logback.core.ConsoleAppender" }
                    .map { targetOf(it) }

            targets shouldContainExactly listOf("System.err")
        }
    })
