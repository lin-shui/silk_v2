package com.silk.web

import kotlinx.coroutines.CancellationException

internal inline fun <T> recoverNonCancellation(
    block: () -> T,
    recover: (Throwable) -> T,
): T {
    return runCatching(block).getOrElse { error ->
        if (error is CancellationException) throw error
        recover(error)
    }
}

internal suspend inline fun <T> recoverSuspendNonCancellation(
    crossinline block: suspend () -> T,
    crossinline recover: (Throwable) -> T,
): T {
    return runCatching { block() }.getOrElse { error ->
        if (error is CancellationException) throw error
        recover(error)
    }
}
