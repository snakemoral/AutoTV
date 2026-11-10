# Estado do projeto (para retomar após limite de uso)
Última atualização: 2026-11-10 (v0.3.1).

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
- Pedido em aberto: aba "Baixados". Os downloads do YouTube Premium são cifrados e ficam na área privada
  do app do YouTube; não dá para ler. Oferecida uma aba de vídeos em arquivo do celular (aguardando resposta).
- NÃO testado em aparelho: v0.3.1 e o login Google da v0.3.0. O login e a leitura das inscrições da conta dependem de
  respostas do YouTube que não foi possível conferir daqui.
- Regra para quem continuar: LER o código antes de escrever; nunca sobrescrever arquivos inteiros sem ler.
