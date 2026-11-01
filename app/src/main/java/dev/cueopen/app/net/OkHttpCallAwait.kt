package dev.cueopen.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Aguarda uma chamada OkHttp sem bloquear uma thread e cancela o socket com a coroutine. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    try {
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    } catch (failure: Throwable) {
        if (continuation.isActive) continuation.resumeWithException(failure)
    }
}
