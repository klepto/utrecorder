package dev.klepto.utrecorder.ut99

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/*
 * Shared between UtCompilerTest and UtCompilerIntegrationTest, which both drive UtCompiler.make against a package
 * sources directory and inspect the task directories it leaves behind.
 */

/**
 * Writes a package holding a single class with the given [body] under [sourcesRoot], and returns its sources
 * directory, as [UtCompiler.make] expects: a directory named after the package, holding a `Classes` folder.
 */
fun createSources(
    sourcesRoot: Path,
    packageName: String,
    body: String = "class $packageName extends Object;",
): Path {
    val sourcesPath = sourcesRoot / packageName
    (sourcesPath / "Classes").createDirectories()
    (sourcesPath / "Classes" / "$packageName.uc").writeText(body)
    return sourcesPath
}

/**
 * Returns the task directories [UtCompiler.make] has left under [tasksDirectory].
 */
fun taskDirectories(tasksDirectory: Path): Set<Path> =
    if (tasksDirectory.exists()) {
        tasksDirectory.listDirectoryEntries("ucc-*").toSet()
    } else {
        emptySet()
    }
