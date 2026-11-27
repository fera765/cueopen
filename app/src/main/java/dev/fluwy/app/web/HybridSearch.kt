package dev.fluwy.app.web

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Corrida entre buscadores (lógica pura de corrotinas, testável na JVM). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object HybridSearch {
    /**
     * Roda as fontes em paralelo. Quando a primeira traz ≥[firstMin] resultados, espera mais [graceMs] pelas
     * outras e devolve o que chegou (na ordem das fontes). Tudo limitado a [totalMs]; fonte com erro = vazia.
     */
    suspend fun <T> race(sources: List<Pair<String, suspend () -> List<T>>>, firstMin: Int, graceMs: Long, totalMs: Long): List<Pair<String, List<T>>> = coroutineScope {
        val jobs = sources.map { (name, f) -> name to async { runCatching { f() }.getOrDefault(emptyList()) } }
        val start = System.currentTimeMillis()
        withTimeoutOrNull(totalMs) {
            val pending = jobs.map { it.second }.toMutableList()
            while (pending.isNotEmpty()) {
                val (d, r) = select<Pair<kotlinx.coroutines.Deferred<List<T>>, List<T>>> { pending.forEach { p -> p.onAwait { p to it } } }
                pending.remove(d)
                if (r.size >= firstMin) break
            }
            val left = totalMs - (System.currentTimeMillis() - start)
            withTimeoutOrNull(minOf(graceMs, left.coerceAtLeast(0))) { jobs.forEach { it.second.await() } }
        }
        val out = jobs.map { (n, d) -> n to (if (d.isCompleted && !d.isCancelled) runCatching { d.getCompleted() }.getOrDefault(emptyList()) else emptyList()) }
        jobs.forEach { it.second.cancel() }
        out
    }
}
