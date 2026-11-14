package com.autotv.player.data

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sincroniza o histórico do AutoTV com o histórico de exibição da conta do YouTube (o mesmo que o
 * app do YouTube mostra no celular e na TV). Só funciona com a conta Google conectada.
 *
 *  - YouTube -> AutoTV: [sync] traz os vídeos vistos em outros aparelhos e o ponto em que pararam.
 *  - AutoTV -> YouTube: o player avisa o YouTube do que está tocando (Account.reportStart/reportProgress).
 */
object HistorySync {
    private const val MIN_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var lastAt = 0L

    val syncing = MutableStateFlow(false)
    /** Espelho de Prefs.syncHistory, para as telas acompanharem. */
    val wanted = MutableStateFlow(true)

    fun init() { wanted.value = Prefs.syncHistory }

    fun setWanted(on: Boolean) {
        Prefs.syncHistory = on
        wanted.value = on
        if (on) syncInBackground(force = true)
    }

    /** true quando há conta conectada e a sincronização está ligada. */
    val enabled: Boolean get() = Account.signedIn.value && Prefs.syncHistory

    /**
     * Traz o histórico da conta e junta com o do app. Devolve a mensagem de erro, ou null se deu certo
     * (ou se não havia o que fazer). Sem [force], não repete a consulta em menos de um minuto.
     */
    suspend fun sync(force: Boolean = false): String? {
        if (!enabled) return null
        return mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (!force && lastAt != 0L && now - lastAt < MIN_INTERVAL_MS) return@withLock null
            syncing.value = true
            try {
                val remote = withContext(Dispatchers.IO) { Account.watchHistory() }
                Store.mergeRemoteHistory(remote)
                lastAt = now
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                syncing.value = false
            }
        }
    }

    fun syncInBackground(force: Boolean = false) {
        scope.launch { sync(force) }
    }
}
