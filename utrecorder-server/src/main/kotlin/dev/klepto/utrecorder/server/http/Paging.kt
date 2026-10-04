package dev.klepto.utrecorder.server.http

import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext

/**
 * Part of a list that a client asks for: a window between two cursors, and a page of that window.
 *
 * [Paging.of] reads one from the parameters of a request.
 *
 * @property offset Items of the window to skip, counted from the end that the page is taken from.
 * @property limit Most items of the page.
 * @property before Key of the item that the window ends right before. If `null`, the window ends at the end of the
 *   list.
 * @property after Key of the item that the window starts right after. If `null`, the window starts at the start of the
 *   list.
 */
class Paging(
    val offset: Int = 0,
    val limit: Int = MAX_LIMIT,
    val before: String? = null,
    val after: String? = null,
) {
    /**
     * Returns the page of [items], in the order of [items].
     *
     * [key] names an item, so that [before] and [after] can name one. The window holds every item between the [after]
     * item and the [before] item, and is empty if the [after] item does not come before the [before] item. If only
     * [before] is given, the page is taken from the end of the window: the last [offset] items are skipped and the
     * last [limit] items kept. Otherwise the page is taken from the start of the window the same way. If [before] or
     * [after] names no item, returns `null` instead.
     */
    fun <T> page(
        items: List<T>,
        key: (T) -> String,
    ): List<T>? {
        val keys = items.map(key)
        if (listOfNotNull(before, after).any { it !in keys }) return null
        val start = after?.let { keys.indexOf(it) + 1 } ?: 0
        val end = before?.let { keys.indexOf(it) } ?: items.size
        val window = items.subList(start, end.coerceAtLeast(start))
        return when {
            before != null && after == null -> window.dropLast(offset).takeLast(limit)
            else -> window.drop(offset).take(limit)
        }
    }

    companion object {
        /**
         * Most items of a page.
         */
        const val MAX_LIMIT = 100

        /**
         * Returns the paging that the `offset`, `limit`, `before` and `after` entries of [parameters] name.
         *
         * A missing `offset` is `0`, a missing `limit` is [MAX_LIMIT], and a `limit` above [MAX_LIMIT] is lowered to
         * it. A missing `before` or `after` is `null`. A repeated entry uses its first value. If `offset` or `limit` is
         * not a non-negative integer, returns `null` instead.
         */
        fun of(parameters: Parameters): Paging? {
            val offset = count(parameters["offset"], 0) ?: return null
            val limit = count(parameters["limit"], MAX_LIMIT) ?: return null
            return Paging(offset, limit.coerceAtMost(MAX_LIMIT), parameters["before"], parameters["after"])
        }

        /**
         * Returns [value] as a non-negative integer, or `null` if it is not one. If [value] is `null`, returns [default]
         * instead.
         */
        private fun count(
            value: String?,
            default: Int,
        ): Int? =
            when (value) {
                null -> default
                else -> value.toIntOrNull()?.takeIf { it >= 0 }
            }
    }
}

/**
 * Answers the page of [items] that the parameters of the request name, as a JSON array in the order of [items].
 *
 * [key] names an item, so that the `before` and `after` parameters can name one. If `offset` or `limit` is not a
 * non-negative integer, or `before` or `after` names no item, answers `400` with an [ErrorResponse] instead.
 */
suspend inline fun <reified T : Any> RoutingContext.respondPage(
    items: List<T>,
    noinline key: (T) -> String,
) {
    val paging = Paging.of(call.parameters)
    val page = paging?.page(items, key)
    when {
        paging == null -> call.respond(BadRequest, ErrorResponse("Limit and offset must be non-negative integers"))
        page == null -> call.respond(BadRequest, ErrorResponse("Before and after must name an item of the list"))
        else -> call.respond(page)
    }
}
