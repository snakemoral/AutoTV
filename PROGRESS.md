# Estado do projeto (para retomar após limite de uso)
Última atualização: 2026-11-10 (v0.5.0).

- Repositório: github.com/snakemoral/AutoTV, branch main. Cada push compila sozinho (GitHub Actions) e
  publica o APK na Release "latest". Trabalhar no clone em /home/claude/autotv-repo.
- v0.2.0 compilou e foi testada pelo usuário no celular e no carro (YouTube, player e controles funcionam).
- v0.3.0 (pedidos do usuário após o teste):
  1. Carro: barra do Android Auto no player reduzida a um botão (o mínimo exigido).
  2. Carro: listas de vídeo desenhadas pelo app (car/CarBrowse.kt), cartões grandes e título pequeno.
     A superfície do carro agora tem dono único (car/CarSurface.kt); as telas se registram como clientes.
  3. Vídeos gravados com qualidade "Automático": manifesto DASH montado em YouTube.buildDash e tocado
     por PlayerHub (modo "trilhas", igual às lives). O modo antigo fica como reserva.
  4. Carro: brilho por camada preta com cor (não alpha), passos de 15%, e arrastar o dedo para cima/baixo.
  5. Inscrições: feed por RSS (todos os canais), importação aceita CSV em qualquer idioma e o .zip do
     Takeout, e login Google por código de TV (data/Account.kt), com a chave cifrada no Android Keystore.
- v0.3.1: rodinha de carregamento no botão central (PlayerHub.loading), recarga ao trocar de rede
  (PlayerHub.watchNetwork), foco de áudio (pausa o Spotify), pausa ao desconectar do carro, celular abre
  em Seguindo, idiomas de áudio sem repetição.
- v0.4.0: aba Baixados (data/LocalVideos.kt, vídeos da galeria; os do YouTube Premium são cifrados e não
  dá para ler), navegador no celular (ui/MoreTabs.kt) e no carro (car/CarBrowser.kt, WebView na tela
  virtual; digitação pelo teclado do Android Auto), carro abre em Seguindo, menu do carro com TV (IPTV)
  no topo, busca forçada em pt-BR. Celular voltou a abrir em Início.
- v0.4.1: manifesto DASH com todos os codecs/qualidades (4K, 60 fps; AV1 só se for o único no topo; HDR só
  sem alternativa) e UMA versão de áudio por idioma (as repetições vinham do menu embutido do PlayerView,
  que lista cada representação). Navegador: botões "pausar/tocar" e "tela cheia" próprios (JS em
  data/Browser.kt) e suporte à tela cheia pedida pelo site, no celular e no carro.
- v0.4.2: "Em alta" do celular usa YouTube.homeFeed() (o quiosque "Trending" antigo não existe mais);
  lista de qualidade mostra também as que o aparelho diz não decodificar (marcadas) e inclui AV1;
  navegador ganhou avançar e favoritos (Prefs.bookmarks) e perdeu os botões próprios de pausar/tela cheia.
  O navegador usa o WebView do sistema (Chromium): codecs, WebGL, Wasm etc. vêm dele, não do app.
- v0.5.0: carro abre em car/CarHub.kt (HubScreen): abas no topo, "Em alta" primeiro, filtros e "Atualizar"
  no topo da lista. Ao vivo: barra de progresso permite voltar no trecho disponível e o rótulo "AO VIVO"
  é o botão de retorno (carro e celular; PlayerHub.liveSeekable/behindLive/seekToLive). Celular: atualizar
  e categorias em "Em alta", filtros em "Seguindo" (só quando a lista traz duração/ao vivo).
  O menu em lista antigo (HomeScreen) ficou só para o modo simples.
- Dúvida em aberto: o usuário não viu qualidades acima de 1080p60 na v0.4.1. Causa não confirmada (ou o
  aparelho não declara suporte, ou o extrator não devolve esses streams). Pedir o nome de um vídeo de teste.
- Confirmado pelo usuário: tela dividida do Android Auto funciona (AutoTV no painel grande + cartão de música).
- NÃO testado em aparelho: v0.5.0. O login e a leitura das inscrições da conta dependem de
  respostas do YouTube que não foi possível conferir daqui.
- Regra para quem continuar: LER o código antes de escrever; nunca sobrescrever arquivos inteiros sem ler.
