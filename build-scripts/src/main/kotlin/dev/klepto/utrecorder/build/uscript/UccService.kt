package dev.klepto.utrecorder.build.uscript

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Serializes the UnrealScript compile tasks of a build.
 *
 * `UtCompiler.make` stages the sources next to `System` and lets UCC write the compiled package into `System`, so
 * every task sharing an installation shares those two directories. Registered with `maxParallelUsages = 1`, so that
 * two packages never compile into one installation at the same time.
 */
abstract class UccService : BuildService<BuildServiceParameters.None>

/**
 * The name [UccService] is registered under, and the name the compile tasks reference it by.
 */
const val UCC_SERVICE = "ucc"
