# AutoTV

Player de TV (listas M3U/IPTV) e YouTube para **Android**, com tela para o **Android Auto**.
Projeto original, sem relação com o APTV.

> **Status:** o código foi escrito mas **ainda não foi compilado nem testado em um aparelho/carro**
> (o ambiente onde foi criado não tem o Android SDK). Só o parser de M3U foi testado.
> Espere ajustar um ou outro erro de compilação e a parte do carro precisa de teste real.

## O que faz
- **Listas M3U**: por URL ou arquivo; busca por canal/categoria; atualizar; favoritos.
- **YouTube**: busca, colar link, *compartilhar do app do YouTube para o AutoTV*, importar playlist (link com `list=`).
- **Android Auto**: abre pelo launcher do carro, navega por Favoritos / listas / categorias / busca no YouTube
  e mostra o vídeo na tela. Ao **voltar para a lista**, o vídeo some mas o **áudio continua**.

## Como gerar o APK

### Opção A: sem computador potente (GitHub)
1. Crie um repositório no GitHub e envie esta pasta inteira (incluindo `.github/`).
2. Aba **Actions** → **Build APK** → aguarde (~5-10 min).
3. Baixe o artefato **AutoTV-debug-apk** e extraia o `app-debug.apk`.

### Opção B: Android Studio
Abra a pasta no Android Studio, aguarde o sync e use **Build > Build APK(s)**.

## Instalar no celular (Xiaomi)
Ative "Instalar apps desconhecidos" para o app que abriu o APK. Se o Play Protect bloquear,
toque em "Instalar mesmo assim" (ou desative a verificação temporariamente).

## Habilitar no Android Auto (Android 14)
1. Android Auto → Configurações → toque **10 vezes** em "Versão" → confirme modo desenvolvedor.
2. Menu ⋮ → **Configurações do desenvolvedor** → ative **Fontes desconhecidas**.
3. Configurações do Android Auto → **Personalizar tela de início/launcher** → habilite **AutoTV**.
4. Conecte ao carro e abra **AutoTV** no launcher.

## Se não aparecer / não funcionar
- Reinicie o Android Auto e reconecte; confira o passo 2 e o 3.
- Tela preta no carro: nem toda central cede a "superfície" para apps de navegação. Teste em outro veículo.
- **YouTube parou de tocar**: o YouTube muda com frequência. Atualize a versão do `NewPipeExtractor`
  em `app/build.gradle.kts` (veja as releases do projeto) e gere o APK de novo.
- Canais M3U que não abrem: alguns provedores exigem um User-Agent específico (`Http.UA_IPTV`).

## Uso responsável
Assista vídeo **somente com o carro parado**. Com o carro em movimento, volte para a lista e use só o áudio.
Use apenas listas/conteúdo que você tem direito de acessar. Extrair streams do YouTube pode violar os
termos de uso do serviço.

## Licença
Usa o NewPipeExtractor (GPL-3.0), portanto este projeto deve ser distribuído sob GPL-3.0.

## Estrutura
- `data/` M3uParser, Store (JSON), YouTube (NewPipeExtractor + OkHttp)
- `player/PlayerHub.kt` um único ExoPlayer para celular e carro
- `ui/` interface do celular (Jetpack Compose)
- `car/` telas do Android Auto (Car App Library: listas, busca e `NavigationTemplate` com a superfície de vídeo)
