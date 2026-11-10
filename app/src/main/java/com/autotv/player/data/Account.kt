package com.autotv.player.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Login na conta Google pelo mesmo método das TVs ("entrar com um código"):
 * o app mostra um código, você digita em google.com/device no navegador (onde faz a senha e a
 * verificação em duas etapas) e o Google devolve ao app uma chave que só vale para o YouTube.
 * O app nunca vê a senha. A chave fica cifrada pelo Android Keystore e pode ser revogada em
 * myaccount.google.com/permissions.
 *
 * As funções de rede são BLOQUEANTES: chame em Dispatchers.IO.
 */
object Account {
    // Identificação pública do cliente "YouTube para TV", o único que aceita login por código.
    private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    private const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube"
    private const val TV_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
    private const val TV_CLIENT_VERSION = "7.20250312.16.00"

    private lateinit var sp: SharedPreferences
    val signedIn = MutableStateFlow(false)

    private var refreshToken: String? = null
    private var accessToken: String? = null
    private var accessExpiresAt = 0L

    class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val intervalSec: Int, val expiresAt: Long)

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("autotv_account", Context.MODE_PRIVATE)
        refreshToken = sp.getString("rt", null)?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        signedIn.value = refreshToken != null
    }

    private fun oauth(url: String, body: JSONObject): JSONObject {
        val (code, text) = Http.postJson(url, body.toString(), mapOf("User-Agent" to TV_UA))
        val json = runCatching { JSONObject(text) }.getOrNull() ?: error("Resposta inesperada do Google (HTTP $code)")
        return json
    }

    /** Passo 1: pede um código para mostrar ao usuário. */
    fun requestCode(): DeviceCode {
        val r = oauth(
            "https://www.youtube.com/o/oauth2/device/code",
            JSONObject()
                .put("client_id", CLIENT_ID)
                .put("scope", SCOPE)
                .put("device_id", UUID.randomUUID().toString().replace("-", ""))
                .put("device_model", "ytlr::")
        )
        val user = r.optString("user_code")
        if (user.isBlank()) error("O Google não devolveu um código: ${r.optString("error", "erro desconhecido")}")
        return DeviceCode(
            user,
            r.optString("verification_url", "https://www.google.com/device"),
            r.getString("device_code"),
            r.optInt("interval", 5).coerceAtLeast(3),
            System.currentTimeMillis() + r.optInt("expires_in", 1800) * 1000L
        )
    }

    /** Passo 2: espera o usuário autorizar. Devolve true quando conectou; false se o código venceu. */
    suspend fun awaitAuthorization(code: DeviceCode): Boolean {
        var wait = code.intervalSec
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(wait * 1000L)
            val r = runCatching {
                oauth(
                    "https://www.youtube.com/o/oauth2/token",
                    JSONObject()
                        .put("client_id", CLIENT_ID)
                        .put("client_secret", CLIENT_SECRET)
                        .put("code", code.deviceCode)
                        .put("grant_type", "http://oauth.net/grant_type/device/1.0")
                )
            }.getOrNull() ?: continue
            when (r.optString("error")) {
                "" -> {
                    val rt = r.optString("refresh_token")
                    if (rt.isBlank()) error("O Google não devolveu a chave de acesso")
                    storeTokens(rt, r)
                    return true
                }
                "authorization_pending" -> Unit
                "slow_down" -> wait += 3
                "access_denied" -> error("Acesso negado na página do Google")
                "expired_token" -> return false
                else -> error("Erro do Google: ${r.optString("error")}")
            }
        }
        return false
    }

    private fun storeTokens(rt: String, r: JSONObject) {
        refreshToken = rt
        accessToken = r.optString("access_token").ifBlank { null }
        accessExpiresAt = System.currentTimeMillis() + (r.optInt("expires_in", 3600) - 60) * 1000L
        // Só guarda no aparelho se der para cifrar; senão o login vale até fechar o app.
        runCatching { Crypto.encrypt(rt) }.getOrNull()?.let { sp.edit().putString("rt", it).apply() }
        signedIn.value = true
    }

    fun signOut() {
        refreshToken = null
        accessToken = null
        sp.edit().remove("rt").apply()
        signedIn.value = false
    }

    @Synchronized
    private fun token(): String {
        val rt = refreshToken ?: error("Você não está conectado à conta Google")
        accessToken?.let { if (System.currentTimeMillis() < accessExpiresAt) return it }
        val r = oauth(
            "https://www.youtube.com/o/oauth2/token",
            JSONObject()
                .put("client_id", CLIENT_ID)
                .put("client_secret", CLIENT_SECRET)
                .put("refresh_token", rt)
                .put("grant_type", "refresh_token")
        )
        val at = r.optString("access_token")
        if (at.isBlank()) {
            if (r.optString("error") == "invalid_grant") {
                signOut()
                error("O acesso à conta foi revogado. Entre de novo.")
            }
            error("Não foi possível renovar o acesso: ${r.optString("error", "erro desconhecido")}")
        }
        accessToken = at
        accessExpiresAt = System.currentTimeMillis() + (r.optInt("expires_in", 3600) - 60) * 1000L
        return at
    }

    // ------------------------------------------------------------------ dados da conta

    /** Vídeos novos das inscrições da conta (a página "Inscrições" do YouTube). */
    fun subscriptionsFeed(): List<Item> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "TVHTML5")
                .put("clientVersion", TV_CLIENT_VERSION)
                .put("hl", "pt")
                .put("gl", "BR")))
            .put("browseId", "FEsubscriptions")
        val (code, text) = Http.postJson(
            "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            body.toString(),
            mapOf("Authorization" to "Bearer ${token()}", "User-Agent" to TV_UA)
        )
        if (code != 200) error("O YouTube recusou a consulta das inscrições (HTTP $code)")
        val tiles = ArrayList<JSONObject>()
        collect(JSONObject(text), "tileRenderer", tiles)
        val items = tiles.mapNotNull { tileToItem(it) }.distinctBy { it.id }
        if (items.isEmpty()) error("O YouTube não devolveu vídeos das inscrições (o formato pode ter mudado)")
        return items
    }

    /** Lista de canais inscritos pela API oficial. Devolve vazio se a API não aceitar esta chave. */
    fun subscribedChannels(): List<Channel> {
        val out = ArrayList<Channel>()
        var page: String? = null
        repeat(40) {
            val url = "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50" +
                (page?.let { "&pageToken=$it" } ?: "")
            val text = runCatching { Http.getWeb(url, mapOf("Authorization" to "Bearer ${token()}")) }.getOrNull() ?: return out
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return out
            val arr = json.optJSONArray("items") ?: return out
            for (i in 0 until arr.length()) {
                val sn = arr.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val id = sn.optJSONObject("resourceId")?.optString("channelId").orEmpty()
                if (id.startsWith("UC")) out += Channel("https://www.youtube.com/channel/$id", sn.optString("title", "Canal"))
            }
            page = json.optString("nextPageToken").ifBlank { null } ?: return out
        }
        return out
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val names = node.keys()
                while (names.hasNext()) {
                    val k = names.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out += v else collect(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private val CHANNEL_IN_JSON = Regex("\"browseId\":\"(UC[\\w-]{22})\"")

    private fun tileToItem(tile: JSONObject): Item? {
        val vid = tile.optJSONObject("onSelectCommand")?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?.takeIf { it.length == 11 }
            ?: tile.optString("contentId").takeIf { it.length == 11 }
            ?: return null
        val meta = tile.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
        val title = text(meta?.optJSONObject("title")) ?: return null
        val channel = meta?.optJSONArray("lines")?.optJSONObject(0)?.optJSONObject("lineRenderer")
            ?.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("lineItemRenderer")
            ?.let { text(it.optJSONObject("text")) }
        val header = tile.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        val thumbs = header?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?: "https://i.ytimg.com/vi/$vid/mqdefault.jpg"
        var live = false
        var duration = 0L
        val overlays = header?.optJSONArray("thumbnailOverlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val st = overlays.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer") ?: continue
                val t = text(st.optJSONObject("text")).orEmpty()
                if (st.optString("style") == "LIVE") live = true
                val parts = t.trim().split(":").map { it.toLongOrNull() }
                if (parts.size in 2..3 && parts.none { it == null }) {
                    duration = parts.fold(0L) { acc, p -> acc * 60 + (p ?: 0) }
                }
            }
        }
        val channelUrl = CHANNEL_IN_JSON.find(tile.toString())?.groupValues?.get(1)
            ?.let { "https://www.youtube.com/channel/$it" }
        return youtubeItem(title, "https://www.youtube.com/watch?v=$vid", channel, thumb, live, if (live) -1 else duration, channelUrl)
    }

    /** Cifra a chave de acesso com uma chave que nunca sai do Android Keystore. */
    private object Crypto {
        private const val ALIAS = "autotv_account"

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            return gen.generateKey()
        }

        fun encrypt(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val data = c.doFinal(plain.toByteArray())
            return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        }

        fun decrypt(stored: String): String {
            val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return String(c.doFinal(data))
        }
    }

    /** Vídeos de "Seguindo": pela conta, se conectada; senão pelos canais salvos no app. */
    fun followingItems(force: Boolean = false): List<Item> {
        var failure: Throwable? = null
        if (signedIn.value) {
            runCatching { subscriptionsFeed() }
                .onSuccess { return it }
                .onFailure { failure = it }
        }
        val channels = Store.following.value
        if (channels.isEmpty()) {
            failure?.let { throw it }
            error("Você ainda não segue canais. Entre na conta Google ou importe as inscrições pelo app no celular.")
        }
        return YouTube.followingFeed(channels, force)
    }
}
