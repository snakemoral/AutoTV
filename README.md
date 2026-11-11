# AutoTV (v0.5.0)

Player de YouTube, TV por lista (IPTV/M3U), vídeos do celular e navegador de internet para Android,
com tela própria no Android Auto.

**Baixar o APK:** [versão mais recente](https://github.com/snakemoral/AutoTV/releases/tag/latest)
(arquivo `AutoTV-debug.apk`, gerado automaticamente a cada mudança no código).

> **Status:** projeto pessoal em desenvolvimento. Testado pelo autor em um Xiaomi 12 Lite (Android 14)
> com Android Auto. As versões saem sem bateria de testes; espere falhas, principalmente no carro.

## O que tem

**No celular**
- **Início:** Em alta (com categorias e botão de atualizar), Ao vivo, Histórico, busca e link colado ou
  compartilhado do YouTube.
- **Seguindo:** vídeos dos canais em que você é inscrito, pela conta Google ou por arquivo do Google Takeout.
- **TV:** listas M3U/IPTV.
- **Baixados:** vídeos guardados no celular (Download, Filmes, Câmera etc.).
- **Web:** navegador com voltar, avançar, recarregar e favoritos.
- **Favoritos:** itens salvos e o interruptor "Interface avançada no carro".
- **Player:** qualidade automática ou manual (até 4K/60 fps, conforme o vídeo e o aparelho), idioma do
  áudio, legendas manuais e automáticas, brilho, tela cheia, indicador e retorno ao vivo.

**No carro (Android Auto)**
- Tela inicial com abas: Em alta, Seguindo, Ao vivo, Histórico, Celular, TV, Web, Favoritos e Buscar.
- Listas de vídeo com miniaturas grandes, filtros e botão de atualizar.
- Player desenhado pelo app: botões na tela, barra de progresso, ±10 s, brilho, qualidade, áudio e legendas.
- Navegador de internet, com digitação pelo teclado do Android Auto.
- Se a tela do carro ficar preta, desligue "Interface avançada no carro" (aba Favoritos do celular)
  para usar o modo simples.

**Conta Google**
Em Seguindo > "Entrar com Google" o app mostra um código para digitar em google.com/device (o mesmo
método das TVs). A senha e a verificação em duas etapas ficam no navegador; o app guarda só uma chave do
YouTube, cifrada, que pode ser revogada em myaccount.google.com/permissions. Sem login, dá para importar
o `subscriptions.csv` (ou o .zip) do Google Takeout.

## Instalar
1. Baixe o `AutoTV-debug.apk` no link acima e instale. Versões novas instalam por cima da anterior.
2. Para aparecer no Android Auto: ative o modo desenvolvedor do Android Auto (10 toques em "Versão"),
   ligue **Fontes desconhecidas** e confira "Personalizar tela de início". Se o app não aparecer,
   instale com KingInstaller + Shizuku.

## Compilar
Cada push na branch `main` compila o APK pelo GitHub Actions (`.github/workflows/build.yml`) e atualiza
a Release `latest`. O APK é assinado com a chave de depuração fixa do repositório (`app/debug.keystore`).

## Se o YouTube parar
Atualize `NewPipeExtractor` em `app/build.gradle.kts` (releases em github.com/TeamNewPipe/NewPipeExtractor).

## Apoie o projeto
O AutoTV é gratuito e de código aberto. Se ele é útil para você e quiser apoiar o desenvolvimento,
pode fazer uma doação de qualquer valor por Pix (chave aleatória):

```
5644f99c-8b48-4aab-991f-963997ef2384
```

No app do seu banco: Pix > Pagar com chave > cole a chave acima. Obrigado!

## Uso responsável
Vídeo e navegador só com o carro parado. Com o carro em movimento use somente o áudio.
Extrair vídeos do YouTube e entrar na conta pelo método das TVs não são usos oficiais e podem violar os
termos do serviço. Licença: GPL-3.0 (NewPipeExtractor).
