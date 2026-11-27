package dev.fluwy.app.util

import kotlinx.coroutines.CancellationException

/** Captura falhas comuns sem transformar cancelamento cooperativo em erro recuperável. */
internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (failure: Throwable) {
    Result.failure(failure)
}
