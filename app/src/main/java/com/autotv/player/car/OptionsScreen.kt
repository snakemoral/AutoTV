package com.autotv.player.car

import androidx.car.app.CarContext
import com.autotv.player.player.Option
import com.autotv.player.player.PlayerHub

/** Lista de escolha (qualidade, idioma, legenda). Ao escolher, aplica e volta. */
class ChoiceScreen(
    ctx: CarContext,
    title: String,
    options: () -> List<Option>,
    onPick: (String) -> Unit
) : PagedScreen(ctx, title, entries = {
    options().map { o ->
        Entry(o.label, if (o.selected) "Selecionado" else null) { s ->
            onPick(o.id)
            s.screenManager.pop()
        }
    }
})

/** Opções do vídeo atual no carro (modo simples; no modo avançado o menu é desenhado na própria tela do vídeo). */
class OptionsScreen(ctx: CarContext) : PagedScreen(ctx, "Opções do vídeo", entries = {
    val list = ArrayList<Entry>()

    val qualities = PlayerHub.qualityOptions.value
    if (qualities.isNotEmpty()) {
        list += Entry("Qualidade", qualities.firstOrNull { it.selected }?.label) { s ->
            s.screenManager.push(ChoiceScreen(s.carContext, "Qualidade", { PlayerHub.qualityOptions.value }) { PlayerHub.selectQuality(it) })
        }
    }
    val audios = PlayerHub.audioOptions.value
    if (audios.isNotEmpty()) {
        list += Entry("Idioma do áudio", audios.firstOrNull { it.selected }?.label) { s ->
            s.screenManager.push(ChoiceScreen(s.carContext, "Idioma do áudio", { PlayerHub.audioOptions.value }) { PlayerHub.selectAudio(it) })
        }
    }
    val subs = PlayerHub.subtitleOptions.value
    if (subs.isNotEmpty()) {
        list += Entry("Legendas", subs.firstOrNull { it.selected }?.label) { s ->
            s.screenManager.push(ChoiceScreen(s.carContext, "Legendas", { PlayerHub.subtitleOptions.value }) { PlayerHub.selectSubtitle(it) })
        }
    }
    if (PlayerHub.hasPrevious()) list += Entry("Vídeo anterior") { PlayerHub.previous(); it.screenManager.pop() }
    if (PlayerHub.hasNext()) list += Entry("Próximo vídeo") { PlayerHub.next(); it.screenManager.pop() }
    list += Entry("Voltar para a lista", "O vídeo some, mas o áudio continua") { s ->
        s.screenManager.pop()
        s.screenManager.pop()
    }
    list += Entry("Parar") {
        PlayerHub.stop()
        it.screenManager.popToRoot()
    }
    list
})
