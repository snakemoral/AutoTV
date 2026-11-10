# AutoTV (v0.3.0)

Player de TV (listas M3U/IPTV) e YouTube para Android, com tela para o Android Auto.

> **Status:** o código NUNCA foi compilado nem testado em aparelho (o ambiente onde foi escrito não tem o
> Android SDK). Só o parser de M3U foi testado. Espere corrigir erros de compilação e ajustes no carro.

## O que tem
**Celular**: abas Início (Em alta / Ao vivo / Histórico, miniaturas, busca, link colado ou compartilhado),
Seguindo (canais do YouTube + importar `subscriptions.csv` do Google Takeout), TV (listas M3U) e Biblioteca
(favoritos). Player próprio: barra de progresso, ao vivo, qualidade, idioma do áudio, legendas (manuais e
automáticas), tela cheia, barra de brilho lateral. O título some junto com os controles.

**Carro (Android Auto)**: Início do YouTube em grade com miniaturas, Ao vivo, Seguindo, busca, histórico,
favoritos e listas. Player desenhado numa tela virtual (VirtualDisplay) com botões, barra de progresso,
±10 s, brilho (+/−), qualidade, áudio e legendas. Em Biblioteca há um interruptor "Interface avançada no
carro": se a tela do carro ficar preta, desligue para usar o modo simples.

**Conta Google:** em Seguindo > "Entrar com Google" o app mostra um código para digitar em
google.com/device (o mesmo método das TVs). A senha e a verificação em duas etapas ficam no navegador;
o app guarda só uma chave do YouTube, cifrada, que pode ser revogada em myaccount.google.com/permissions.
Sem login, dá para importar o `subscriptions.csv` (ou o .zip) do Google Takeout.

## Gerar o APK (GitHub)
1. Envie esta pasta para o repositório (`git add -A`, `git commit`, `git push`). O arquivo
   `.github/workflows/build.yml` gera o APK na aba **Actions** (artefato `AutoTV-debug-apk`).
2. O APK agora é assinado com uma chave fixa (`app/debug.keystore`): novas versões instalam POR CIMA
   da anterior. **Só desta vez**, desinstale a versão antiga antes (a assinatura mudou).

## Android Auto
Veja as configurações: modo desenvolvedor (10 toques em "Versão"), **Fontes desconhecidas** ligado e o app
precisa aparecer em "Personalizar tela de início". Se não aparecer, instale com KingInstaller + Shizuku.

## Se o YouTube parar
Atualize `NewPipeExtractor` em `app/build.gradle.kts` (releases em github.com/TeamNewPipe/NewPipeExtractor).

## Uso responsável
Vídeo só com o carro parado. Com o carro em movimento use somente o áudio (volte para a lista).
Extrair streams do YouTube pode violar os termos do serviço. Licença: GPL-3.0 (NewPipeExtractor).
