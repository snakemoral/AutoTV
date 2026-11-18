package com.autotv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

/** Uma mensagem do chat ao vivo. [amount] = valor do Super Chat, quando é uma mensagem paga. */
class ChatMessage(val id: String, val author: String, val text: String, val amount: String? = null)

/**
 * Chat de transmissões ao vivo do YouTube, só para ler (como no site, sem conta). Usa a mesma consulta do
 * site: a página do vídeo dá o primeiro "ponto de continuação" do chat, e cada consulta seguinte traz as
 * mensagens novas e o próximo ponto, com o tempo que o YouTube pede para esperar.
 */
object LiveChat {
    private const val WEB_VERSION = "2.20250312.01.00"
    private const val KEEP = 150

    private fun call(endpoint: String, body: JSONObject): JSONObject {
        val context = JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", WEB_VERSION).put("hl", "pt").put("gl", "BR"))
        val (code, text) = Http.postJson(
            "https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false",
            body.put("context", context).toString(),
            mapOf(
                "User-Agent" to Http.UA_WEB,
                "Origin" to "https://www.youtube.com",
                "X-YouTube-Client-Name" to "1",
                "X-YouTube-Client-Version" to WEB_VERSION
            )
        )
        if (code != 200) error("HTTP $code")
        return JSONObject(text)
    }

    private fun find(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                val names = node.keys()
                while (names.hasNext()) find(node.opt(names.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) find(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun continuationOf(o: JSONObject?): Pair<String, Long>? {
        val list = o?.optJSONArray("continuations") ?: return null
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            for (kind in listOf("invalidationContinuationData", "timedContinuationData", "reloadContinuationData")) {
                val d = c.optJSONObject(kind) ?: continue
                val token = d.optString("continuation").takeIf { it.isNotBlank() } ?: continue
                return token to d.optLong("timeoutMs", 5_000L)
            }
        }
        return null
    }

    /** Primeiro ponto do chat do vídeo, ou erro se o vídeo não tem chat (desativado ou não é ao vivo). */
    private fun firstContinuation(videoId: String): String {
        val page = call("next", JSONObject().put("videoId", videoId))
        val renderer = find(page, "liveChatRenderer") ?: error("Esta transmissão não tem chat")
        return continuationOf(renderer)?.first ?: error("Esta transmissão não tem chat")
    }

    private fun textOf(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            if (r.has("text")) { sb.append(r.optString("text")); continue }
            val emoji = r.optJSONObject("emoji") ?: continue
            // Emoji comum: o próprio caractere. Emoji do canal: o atalho (":nome:").
            val custom = emoji.optBoolean("isCustomEmoji", false)
            val id = emoji.optString("emojiId")
            sb.append(
                if (!custom && id.isNotBlank()) id
                else emoji.optJSONArray("shortcuts")?.optString(0)?.takeIf { it.isNotBlank() } ?: ""
            )
        }
        return sb.toString()
    }

    private fun parse(actions: JSONArray?): List<ChatMessage> {
        if (actions == null) return emptyList()
        val out = ArrayList<ChatMessage>()
        for (i in 0 until actions.length()) {
            val item = actions.optJSONObject(i)?.optJSONObject("addChatItemAction")?.optJSONObject("item") ?: continue
            item.optJSONObject("liveChatTextMessageRenderer")?.let { m ->
                val text = textOf(m.optJSONObject("message"))
                if (text.isNotBlank()) out += ChatMessage(m.optString("id"), textOf(m.optJSONObject("authorName")), text)
            }
            item.optJSONObject("liveChatPaidMessageRenderer")?.let { m ->
                out += ChatMessage(
                    m.optString("id"), textOf(m.optJSONObject("authorName")),
                    textOf(m.optJSONObject("message")), textOf(m.optJSONObject("purchaseAmountText")).ifBlank { null }
                )
            }
        }
        return out
    }

    /**
     * Mensagens do chat do vídeo [videoId], sempre as [KEEP] mais recentes (a mais nova no fim). Fica
     * consultando enquanto alguém estiver coletando; para quando a tela fecha o chat.
     */
    fun watch(videoId: String): Flow<List<ChatMessage>> = flow {
        var token = firstContinuation(videoId)
        val shown = ArrayList<ChatMessage>()
        val seen = LinkedHashSet<String>()
        var failures = 0
        while (true) {
            val wait = try {
                val res = call("live_chat/get_live_chat", JSONObject().put("continuation", token))
                val chat = res.optJSONObject("continuationContents")?.optJSONObject("liveChatContinuation")
                    ?: error("O chat desta transmissão terminou")
                val fresh = parse(chat.optJSONArray("actions")).filter { it.id.isBlank() || seen.add(it.id) }
                while (seen.size > 2_000) seen.remove(seen.first())
                if (fresh.isNotEmpty()) {
                    shown += fresh
                    while (shown.size > KEEP) shown.removeAt(0)
                    emit(shown.toList())
                }
                val (next, timeout) = continuationOf(chat) ?: error("O chat desta transmissão terminou")
                token = next
                failures = 0
                timeout.coerceIn(1_500L, 10_000L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Queda de internet: tenta de novo algumas vezes antes de desistir.
                if (++failures > 5) throw e
                5_000L
            }
            delay(wait)
        }
    }.flowOn(Dispatchers.IO)
}
