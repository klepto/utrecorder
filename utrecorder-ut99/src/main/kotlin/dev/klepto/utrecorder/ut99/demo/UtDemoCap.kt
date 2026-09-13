package dev.klepto.utrecorder.ut99.demo

import kotlin.time.Duration

/**
 * The cap that ends a Bunny Track run, as the messages and markers of a demo report it.
 *
 * Read one through [UtDemo.cap], which is `null` for a demo that holds no cap.
 *
 * @property capper The player whose run the demo records, or `null` when nothing in the demo tells them from a
 * teammate.
 * @property id The unique cap id the demo's markers carry, or `null` when the demo holds no marker.
 * @property runTime The length of the run, or `null` when the demo reports none.
 * @property demoTime The demo time the cap arrives at.
 */
data class UtDemoCap(
    val capper: String?,
    val id: String?,
    val runTime: Duration?,
    val demoTime: Duration,
)
