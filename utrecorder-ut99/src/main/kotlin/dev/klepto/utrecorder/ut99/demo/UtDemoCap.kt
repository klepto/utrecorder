package dev.klepto.utrecorder.ut99.demo

import kotlin.time.Duration

/**
 * The cap that ends a Bunny Track run, as the messages and markers of a demo report it.
 *
 * Use [UtDemo.read] to read the cap of a demo.
 *
 * @property capper The player whose run the demo records. If nothing in the demo tells them from a teammate, it is
 * `null` instead.
 * @property id The unique cap id the markers of the demo carry. If the demo holds no marker, it is `null` instead.
 * @property runTime The length of the run. If the demo reports none, it is `null` instead.
 * @property demoTime The demo time the cap arrives at.
 */
data class UtDemoCap(
    val capper: String?,
    val id: String?,
    val runTime: Duration?,
    val demoTime: Duration,
) {
    /**
     * The demo time the run starts at, [runTime] before [demoTime]. If [runTime] is `null`, it is `null` too.
     */
    val runStart: Duration?
        get() = runTime?.let { demoTime - it }

    /**
     * The demo time the Bunny Track timer of the run stops at: [runStart] plus [runTime], which is [demoTime]. If
     * [runTime] is `null`, it is `null` too.
     */
    val runStop: Duration?
        get() = runTime?.let { demoTime }
}
