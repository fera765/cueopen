package dev.fluwy.app.agent

import dev.fluwy.app.data.ChatItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Completa a imagem dos cards que vieram sem foto, usando a imagem principal da página do item (em paralelo, com prazo). */
object CardImages {
    suspend fun fill(cards: ChatItem.Cards, timeoutMs: Long = 7_000, fetch: suspend (String) -> String?): ChatItem.Cards = coroutineScope {
        val filled = cards.items.map { e ->
            async {
                if (e.image.isNotBlank() || !e.url.startsWith("http")) e
                else withTimeoutOrNull(timeoutMs) { fetch(e.url) }?.let { img -> e.copy(image = img) } ?: e
            }
        }.awaitAll()
        cards.copy(items = filled)
    }
}
