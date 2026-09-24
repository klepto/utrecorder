package dev.klepto.utrecorder.build.uscript

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Stateless build service that stands for the UnrealScript compiler. A task that uses it holds one of its usages for
 * as long as it runs, so a limit on usages limits how many compiles run at a time.
 */
abstract class UccService : BuildService<BuildServiceParameters.None>

/**
 * Name of the shared [UccService] of a build.
 */
const val UCC_SERVICE = "ucc"
