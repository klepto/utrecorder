package dev.klepto.utrecorder.build.uscript

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Serializes the UnrealScript compile tasks of a build, so that two packages never compile into one installation at
 * the same time. Registered with `maxParallelUsages = 1`.
 */
abstract class UccService : BuildService<BuildServiceParameters.None>

/**
 * The name [UccService] is registered under, and the name the compile tasks reference it by.
 */
const val UCC_SERVICE = "ucc"
