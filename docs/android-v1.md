# OpenScript Android v1.0

O aplicativo está em [`android/`](../android/) e usa Kotlin, Jetpack Compose, CameraX e Media3. As telas, o teleprompter, a câmera e o processamento de vídeo são nativos. O servidor Django atende às operações de conta, roteiros e preferências por meio da [API v1](native-api.md).

Esta entrega é Android. A implementação iOS ainda não foi iniciada. A interface desta versão está em português.

## Abrir e executar

1. No Android Studio, abra a pasta `android/` como projeto.
2. Instale a plataforma Android SDK 37 pelo SDK Manager e aceite as licenças solicitadas. O Android Studio configura o caminho local do SDK em `android/local.properties`; esse caminho depende da máquina.
3. Use JDK 25 para o processo Gradle, conforme [`gradle-daemon-jvm.properties`](../android/gradle/gradle-daemon-jvm.properties). O projeto inclui a configuração de provisionamento dessa versão. As opções Java 11 de compilação do módulo não substituem o requisito do JDK do Gradle.
4. Aguarde a sincronização das dependências e execute o módulo `app` em um aparelho ou emulador.

O mínimo é Android 8.0, API 26. A compilação usa SDK 37 e o `targetSdk` é 37. O wrapper incluído no repositório deve ser usado para manter a versão de Gradle definida pelo projeto.

No PowerShell, a partir da raiz do repositório:

```powershell
Set-Location .\android
.\gradlew.bat assembleDebug
```

O APK de desenvolvimento é gerado em `android/app/build/outputs/apk/debug/app-debug.apk`. Para instalar em um dispositivo conectado com depuração autorizada:

```powershell
.\gradlew.bat installDebug
```

## Uso sem conta e dados locais

É possível escrever, importar texto UTF-8, ensaiar com o teleprompter, gravar, reproduzir e exportar sem entrar em uma conta. Essas operações locais não dependem do servidor.

O aplicativo mantém um único roteiro atual, em texto puro e sem campo de título. A aba **Roteiro** abre diretamente esse texto. As alterações são salvas automaticamente; fechar o editor, usar o texto na câmera e encerrar normalmente o aplicativo também concluem o salvamento. Ao reabrir, o último texto é restaurado, incluindo espaços e quebras de linha. Apagar todo o conteúdo mantém o roteiro vazio na próxima abertura.

O roteiro, os ajustes e os takes são guardados no espaço privado do aplicativo. Visitante e cada conta têm dados separados. Entrar ou sair não importa automaticamente o texto do visitante para a conta, nem mistura seus arquivos. Roteiros de versões anteriores continuam preservados nos dados locais.

Importar um arquivo `.txt` ou escolher um texto da conta substitui o conteúdo do roteiro atual. Esse passa a ser o texto lembrado na próxima abertura. Os takes novos são identificados pela data e hora da gravação.

Os originais MP4 permanecem no espaço privado do aplicativo. **Desinstalar o aplicativo ou limpar seus dados apaga essas cópias locais.** Use **Salvar** para guardar uma cópia no destino escolhido pelo seletor de arquivos do Android. **Compartilhar** abre o seletor de aplicativos do sistema; não há publicação automática em redes sociais.

A biblioteca usa escrita atômica. Takes concluídos que tenham ficado sem índice após uma interrupção podem ser recuperados pelos metadados do MP4. Arquivos incompletos ou ilegíveis são preservados no armazenamento privado, mas não são apresentados como takes concluídos. Se um JSON local estiver corrompido, suas versões brutas são preservadas em uma pasta privada de recuperação antes de uma nova gravação da biblioteca.

## Câmera e teleprompter

### Abertura e rotação

A abertura exibe a marca OpenScript em arte vetorial, com uma breve animação e transição para o estúdio. Ela aparece ao iniciar o aplicativo e não se repete ao girar a tela.

O ícone do aplicativo preserva a marca original, com camadas adaptáveis e uma versão monocromática para os temas do Android. Os arquivos por densidade e a imagem de 512 pixels estão incluídos no projeto. Para regenerá-los a partir de `studio/static/studio/app-icon.svg`, execute `node android/tools/build-icons.cjs` na raiz do repositório, com a biblioteca `sharp` disponível. A pasta `android/branding/` contém a imagem de 512 pixels e a prévia dos formatos.

O menu de navegação usa uma barra arredondada com os quatro atalhos sempre identificados. A aba ativa tem fundo destacado, ícone vermelho e um pequeno marcador; as cores mudam suavemente sem deslocar os controles. O componente `StudioNavigation.kt` inclui prévias para o Android Studio, inclusive com fonte ampliada.

Os controles do estúdio ficam em um painel com cantos arredondados, fundo escuro e divisões discretas. **Editar roteiro** e **Ajustes** têm ícones próprios. O valor da **Velocidade** é um atalho separado: abre um painel compacto com deslizador entre 10 e 100 px/s e botões de diminuir/aumentar em 5 px/s. A mudança é imediata, usa a mesma preferência dos ajustes completos e é salva localmente, sem reiniciar a leitura ou alterar o roteiro. O painel rápido fica indisponível durante preparação e gravação. Em telas estreitas ou com fonte ampliada, o atalho ocupa uma segunda linha. **Ensaiar** e **Trocar câmera** usam botões circulares nas laterais do botão de gravação, que mantém o destaque vermelho e uma resposta breve ao toque. Ao ensaiar, o ícone muda para pausa e a ação passa a ser **Pausar leitura**. Na horizontal, as ações de captura ficam na lateral direita, com rolagem quando a altura não for suficiente.

O aplicativo acompanha a rotação permitida pelo Android. Na horizontal, a navegação fica à esquerda e os controles de preparação à direita. Editor, conta, documentos, reprodução e exportação usam o espaço disponível e oferecem rolagem quando necessário, inclusive com o teclado aberto.

Girar durante um take não o encerra nem interrompe a captura. O MP4 mantém a orientação escolhida no início da gravação; a próxima gravação adota a orientação atual. A prévia acompanha a tela, e o texto continua fora do vídeo.

Na primeira entrada no estúdio, o aplicativo pede acesso à câmera e ao microfone. Sem câmera autorizada, ainda é possível preparar roteiros. Com o microfone desativado, a gravação sem áudio exige uma confirmação explícita.

O teleprompter aparece sobre a prévia e oferece velocidade, tamanho, ajuste automático do texto à tela, fonte, cor, opacidade de fundo e visibilidade. É possível ensaiar sem gravar. **O texto do teleprompter não é incorporado ao MP4.** Chegar ao fim do texto também não encerra a gravação.

Um roteiro preenchido ganha uma faixa de leitura na região superior da prévia. A linha que cruza a guia mantém a cor escolhida, e as demais ficam atenuadas. O destaque acompanha a rolagem manual, o ensaio e a gravação. Ele usa as linhas realmente renderizadas, respeitando quebras, fonte, tamanho e rotação, sem modificar os caracteres ou as métricas do texto ao trocar a linha em foco. O espaço de leitura no fim permite que a última linha também alcance a faixa. O roteiro permanece um único texto acessível, e a faixa é apenas parte da prévia, fora do vídeo gravado.

A prévia preenche toda a área da câmera, preservando a proporção da imagem e cortando as bordas que excederem esse espaço. Isso elimina as faixas pretas sem esticar a imagem. Esse preenchimento afeta apenas a visualização; o MP4 mantém o enquadramento completo da captura.

A prévia frontal pode ser espelhada para facilitar o enquadramento. Esse ajuste afeta a visualização; o vídeo é capturado sem espelhamento. A câmera traseira não é espelhada por esse controle.

A captura usa CameraX e uma qualidade disponível no aparelho. A tela não tem cabeçalho, e a câmera já ocupa toda a janela por trás da interface antes de iniciar o take. Após a contagem regressiva, navegação e controles desaparecem gradualmente por opacidade, revelando a câmera também nos espaços que antes cobriam. A prévia e a área do roteiro permanecem no mesmo tamanho e posição, sem reenquadramento durante a transição. Durante o take, ficam visíveis a prévia da câmera no máximo da tela, o roteiro quando habilitado e o botão de parar, que ocupa o mesmo lugar do botão de gravar. As barras do Android mantêm seu estado. Ao encerrar, os controles reaparecem gradualmente. Esse comportamento funciona em retrato e paisagem sem reiniciar a câmera.

Colocar o aplicativo em segundo plano ou perder o acesso à câmera encerra a captura em andamento. A gravação não é retomada automaticamente ao voltar. Um take interrompido que permaneça reproduzível é mantido para revisão e exportação.

## Exportação e compartilhamento

A aba **Takes** apresenta uma galeria de cartões com prévia maior, duração, data, tamanho real do arquivo em MB/GB e resolução, ordenados do mais recente para o mais antigo. A galeria adapta o número de colunas ao espaço disponível. Nomes automáticos são apresentados como **Take**, com a data apenas na linha secundária; nomes escolhidos pela pessoa são preservados. Tocar na prévia abre a reprodução, e **Exportar** é a única ação principal do cartão. O menu de três pontos oferece **Renomear**, **Compartilhar** e **Excluir**, mantendo a confirmação antes da exclusão. Renomear aceita um nome de 1 a 120 caracteres e altera somente o índice local, sem mover ou modificar o vídeo. O nome só muda na interface depois de ser salvo; falhas mantêm o nome anterior e o texto em edição. Quando não há gravações, **Abrir estúdio** leva à câmera. É possível salvar ou compartilhar o original sem processá-lo novamente.

**Exportar** abre uma tela própria na mesma Activity, com navegação de volta, conteúdo rolável e ações fixas no rodapé. O resumo dos ajustes e o botão **Exportar vídeo** permanecem visíveis durante a rolagem; progresso, cancelamento e ações de salvar/compartilhar usam a mesma área. Os destinos são agrupados por rede com ícones vetoriais locais: Instagram, YouTube e Facebook expandem suas variantes; redes com uma opção abrem diretamente seus ajustes. Cada variante mantém o perfil completo do catálogo. **Personalizada** permite escolher formato MP4 ou WebM, proporção, resolução de 360p/720p/1080p, qualidade compacta/equilibrada/alta e teto de 24/30/60 FPS. A proporção inicial acompanha a orientação do take, e trocar ajustes invalida a cópia preparada anteriormente. Voltar aos destinos mantém os ajustes personalizados durante a abertura atual. Voltar aos takes restaura a posição da galeria. Durante a exportação, voltar solicita confirmação para cancelar; durante a cópia para o destino, o retorno fica bloqueado até a conclusão.

O processamento usa Media3 para gerar uma nova cópia MP4 H.264/AAC ou WebM VP8/Opus, quando os codificadores do aparelho oferecerem o formato escolhido. A saída usa a extensão e o MIME correspondentes também ao salvar e compartilhar. FPS acima do original não criam quadros adicionais; a resolução não amplia a imagem original. Se o aparelho não oferecer os codificadores WebM, a interface orienta escolher MP4. O original não é modificado. Mantenha o aplicativo aberto durante o processamento; cancelar uma exportação remove sua saída incompleta e preserva o take original.

Se o decodificador padrão falhar, a reprodução e a exportação tentam uma vez os decodificadores de software disponíveis no aparelho. O processamento pode ficar mais lento. Se essa alternativa também falhar, o original continua disponível para salvar e compartilhar.

O catálogo tem 14 opções, incluindo **Personalizado**, e é compartilhado com a web em [`studio/static/studio/export-catalog.json`](../studio/static/studio/export-catalog.json). O Gradle incorpora esse arquivo aos recursos do aplicativo durante a compilação. As opções incluem Instagram, TikTok, YouTube, Facebook, LinkedIn, X, WhatsApp, Pinterest e Kwai.

No perfil personalizado, é possível escolher:

| Ajuste | Opções |
| --- | --- |
| Formato | MP4 (H.264/AAC) e WebM (VP8/Opus), conforme os codificadores do aparelho |
| Proporção | 9:16, 4:5, 1:1, 16:9 e 2:3 |
| Resolução solicitada | 360p, 720p e 1080p, usando o lado curto |
| Qualidade | Compacta, equilibrada e alta |
| Quadros por segundo | Teto de 24, 30 ou 60 FPS, sem criar quadros além do original |

A imagem completa é ajustada à proporção escolhida, com preenchimento quando necessário. A resolução fica limitada pela gravação original para evitar ampliação artificial; o aplicativo informa quando essa limitação altera as dimensões previstas. O codificador também pode exigir uma resolução compatível com o aparelho.

As orientações de duração dos perfis são sugestões para publicação. Elas não cortam o vídeo nem interrompem a gravação. A opção **Salvar** usa o seletor de destino do Android, por meio do Storage Access Framework. A saída processada fica no cache até ser salva em um destino escolhido. Cópias temporárias com mais de 24 horas podem ser removidas ao iniciar uma nova exportação; o Android também pode liberar o cache quando necessário.

## Conta e conexão com o servidor

O endereço do servidor é uma configuração interna da compilação. A aba **Conta** oferece acesso, cadastro e recuperação de senha, sem expor endereço, porta ou configuração de servidor. O cliente adiciona os caminhos da API. A versão de produção aceita HTTPS; não há uma liberação geral de HTTP.

Para definir o endereço, use a propriedade Gradle `openscript.apiOrigin`, por exemplo `openscript.apiOrigin=https://seu-dominio` em `android/gradle.properties`, ou passe `-Popenscript.apiOrigin=https://seu-dominio` ao compilar. Informe apenas a origem, sem `/api/v1/`. Sem essa propriedade, debug usa `http://10.0.2.2:8000`; release mantém o acesso à conta indisponível até o endereço oficial ser definido na compilação. Endereços já salvos por versões anteriores continuam sendo respeitados para preservar sessões existentes, sem oferecer edição na interface.

O aplicativo oferece login por e-mail e senha, cadastro, recuperação de senha e saída. O cadastro exige abrir os Termos de Uso e a Política de Privacidade, chegar ao fim de cada documento, confirmar a leitura e marcar os dois aceites separadamente. O link de recuperação recebido por e-mail abre o fluxo web de redefinição de senha.

O token fica cifrado com uma chave do Android Keystore. Ele não é mostrado na interface, e o backup dos dados do aplicativo está desativado. Ao receber `401`, o aplicativo pede novo login e preserva os arquivos locais da conta. Não há renovação automática de token. A saída limpa o acesso local; se o servidor estiver indisponível, o token remoto pode continuar válido até expirar.

A sincronização é explícita: buscar um texto da conta para usar como roteiro atual, enviar o roteiro em edição, aplicar ajustes da conta ou enviar os ajustes do aparelho. A escolha de um texto da conta é feita pelo conteúdo, sem exibir títulos. Aplicar ajustes da conta substitui as preferências locais dessa área. Conflitos de revisão mantêm a edição local para comparação; o aplicativo não faz mesclagem automática. Vídeos não são enviados ao servidor.

Login nativo com Google não está implementado. O login Google que possa estar habilitado no site não cria esse recurso no aplicativo.

### Desenvolvimento local

Prepare o Django, as migrações e as opções de envio de e-mail seguindo [`docs/native-api.md`](native-api.md). Na raiz do repositório, o servidor pode ser iniciado com:

```powershell
.\venv\Scripts\python.exe manage.py migrate
.\venv\Scripts\python.exe manage.py runserver 127.0.0.1:8000
```

No emulador Android, a compilação **debug** usa por padrão `http://10.0.2.2:8000` internamente para acessar esse servidor no computador. A configuração de rede debug limita HTTP a `10.0.2.2`, `127.0.0.1` e `localhost`; endereços HTTP arbitrários da rede local continuam bloqueados.

Como alternativa, com um aparelho ou emulador conectado ao ADB:

```powershell
adb reverse tcp:8000 tcp:8000
```

Para essa alternativa, gere o APK debug com a origem interna `http://127.0.0.1:8000`:

```powershell
# Dentro de android/
.\gradlew.bat assembleDebug "-Popenscript.apiOrigin=http://127.0.0.1:8000"
```

Se o ADB não estiver no `PATH`, use o executável em `platform-tools` do SDK. Quando houver vários dispositivos conectados, selecione o alvo com `adb -s SERIAL reverse tcp:8000 tcp:8000`.

## Verificação

Execute os comandos abaixo dentro de `android/`, com o SDK e o JDK configurados:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

Com um emulador iniciado ou aparelho conectado, desbloqueado e autorizado:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Validação desta entrega em 19/10/2026: compilação debug, 38 testes de unidade e 16 testes instrumentados passaram, e o lint terminou sem erros. Os testes instrumentados foram executados no emulador API 37, com câmera simulada e entrada de microfone do computador desativada. Eles cobrem editor com campo único sem título, preservação exata do texto e de suas quebras de linha, restauração da última edição após encerramento imediato, roteiro intencionalmente vazio, isolamento por conta, preservação dos roteiros legados, captura com trilha de áudio, pausa e retomada no mecanismo de captura, persistência de MP4, exportação H.264/AAC quadrada a 360p, limite de 24 FPS, preservação do original por SHA-256, cancelamento durante preparação, interface de gravação com apenas o botão de parar, retorno dos controles ao encerrar, rotação da tela durante a captura sem recriar a Activity ou interromper o take, orientação do take seguinte, recuperação de JSON e contrato HTTP de conta/roteiros/documentos legais com servidor local simulado. O teste HTTP não usa contas reais nem envia e-mails. Essa verificação não substitui testes em aparelhos reais.

Para verificações no emulador, configure a câmera simulada do AVD e mantenha a entrada de microfone do computador desativada. Imagem e áudio desse ambiente não representam uma câmera física. Confira manualmente permissões, gravação silenciosa, transições da interface ao gravar e parar, rotação durante o take, interrupção ao sair do aplicativo, reprodução do original, exportação, destino salvo e isolamento entre visitante e contas.

Na atualização do fluxo de exportação, a compilação, o lint e os 43 testes de unidade passaram. Os cinco casos novos cobrem formato/extensão/MIME, FPS e limite do original. Com autorização para atualizar o emulador pelo Run do Android Studio, as telas reais de Takes, destinos e ajustes personalizados foram conferidas no emulador API 37. Foram geradas e decodificadas integralmente uma cópia social MP4 H.264/AAC e uma cópia personalizada WebM VP8/Opus em 360 × 640, com teto de 24 FPS (234 quadros, aproximadamente 24 FPS medidos). Os hashes SHA-256 dos cinco takes originais permaneceram iguais antes e depois dessa verificação. Os testes instrumentados descritos acima são da verificação anterior; não foram executados novamente nesta atualização. WebM e sincronismo de áudio ainda devem ser conferidos em aparelhos físicos de fabricantes diferentes.

Na reorganização das telas de Takes e exportação, passaram 51 testes de unidade e o lint terminou sem erros. Os novos casos cobrem validação de nomes e preservação de todos os perfis ao agrupá-los por rede. Os testes instrumentados de persistência de nomes, isolamento entre contas, falha de escrita atômica e leitura do tamanho foram compilados, sem nova execução da suíte instrumentada. No app atualizado pelo Run, foram conferidos o rodapé fixo durante a rolagem, o agrupamento Instagram → Reels, a volta aos destinos/Takes, paisagem e fonte ampliada em 1,5×. Uma renomeação temporária feita pela interface foi persistida e depois desfeita pela própria interface, com restauração integral do índice original. As configurações do emulador foram restauradas, e o SHA-256 do vídeo original permaneceu igual.

No refinamento visual do painel do estúdio, a compilação e o lint terminaram sem erros. O visual foi conferido no app atualizado em retrato, paisagem e com fonte em 1,5×; abrir **Ajustes** continuou acessando os controles de velocidade. A rotação e a escala da fonte do emulador foram restauradas após a conferência. Essa alteração visual não incluiu nova execução dos testes de captura nem geração de uma gravação de teste.

No ajuste rápido de velocidade e na faixa de leitura, passaram 55 testes de unidade; os quatro novos casos cobrem seleção por limites reais das linhas, linhas vazias, alcance da última linha e preservação do espaçamento final quando suficiente. A compilação e o lint terminaram sem erros, e os testes instrumentados existentes foram compilados, sem nova execução da suíte de captura. No emulador atualizado, foram conferidos o painel rápido em retrato/paisagem/fonte em 1,5×, o ajuste de 83 para 88 px/s e sua restauração, além de iniciar e pausar o ensaio com a linha em foco mudando durante a rolagem. O SHA-256 da biblioteca inteira permaneceu idêntico após restaurar a velocidade, confirmando a preservação do roteiro e dos ajustes. A conferência não copiou o conteúdo do roteiro para o projeto. As configurações do emulador foram restauradas.

## Antes de distribuir

O identificador atual é `com.example.open_script`. Uma publicação exige um pacote próprio, configuração de assinatura de release e gestão segura da chave de assinatura. Também é necessário validar o aplicativo em aparelhos Android reais, incluindo câmera frontal e traseira, sincronismo de áudio, gravações longas, falta de espaço, interrupções e codificadores de fabricantes diferentes.

Esta implementação não inclui distribuição em loja, assinatura de produção, implementação iOS, login Google nativo, upload de vídeos ou publicação automática nas plataformas dos perfis de exportação.
