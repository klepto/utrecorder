package dev.klepto.utrecorder.build.web

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Short hash of the commit checked out in a git repository.
 *
 * Obtains nothing when the directory is not a git checkout, when `git` cannot resolve `HEAD`, or when `git` cannot be
 * started. A `git` that cannot be started is logged as a warning.
 */
abstract class GitCommitSource : ValueSource<String, GitCommitSource.Parameters> {
    /**
     * Parameters of the commit lookup.
     */
    interface Parameters : ValueSourceParameters {
        /**
         * Root directory of the git checkout.
         */
        val repository: DirectoryProperty
    }

    /**
     * Service that runs `git`.
     */
    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String? {
        val repository = parameters.repository.get().asFile
        if (!repository.resolve(".git").exists()) return null

        return revisionCatching(repository)
            .onFailure { logger.warn("Cannot read the git commit: ${it.message}") }
            .getOrNull()
    }

    /**
     * Returns the short hash of `HEAD` in [repository], or the failure that kept `git` from starting. If `git` exits
     * with an error, returns `null` instead.
     */
    private fun revisionCatching(repository: File): Result<String?> =
        runCatching {
            val output = ByteArrayOutputStream()
            val result =
                exec.exec {
                    commandLine("git", "rev-parse", "--short", "HEAD")
                    workingDir = repository
                    standardOutput = output
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            output
                .toByteArray()
                .decodeToString()
                .trim()
                .takeIf { result.exitValue == 0 && it.isNotEmpty() }
        }

    companion object {
        private val logger = Logging.getLogger(GitCommitSource::class.java)
    }
}
