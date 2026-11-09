# Estado do projeto (para retomar após limite de uso)
Última atualização: 2026-11-08.
- Código completo e alinhado entre data/, player/, ui/ e car/ (verificado só por leitura + checagem de
  sintaxe com kotlinc; NÃO compilado com o Android SDK).
- Reparado nesta rodada: data/Models.kt (reconstruído), PagedScreen (parâmetro `loader`),
  YouTube.kiosk/followingFeed, OptionsScreen e diálogo de opções do celular, duplicatas (ImageCache,
  itemSubtitle, formatDuration), histórico gravado em PlayerHub.start().
- Pendente: 1) compilar de verdade (GitHub Actions) e corrigir erros; 2) testar no carro;
  3) revisão independente de compile errors (não concluída por limite de uso).
- Regra para quem continuar: LER esta pasta antes de escrever; nunca sobrescrever arquivos inteiros.
