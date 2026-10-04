package dev.klepto.utrecorder.server.http

import kotlinx.serialization.Serializable

/**
 * Body of every `4xx` answer of the API.
 *
 * @property error What went wrong.
 */
@Serializable
data class ErrorResponse(
    val error: String,
)
