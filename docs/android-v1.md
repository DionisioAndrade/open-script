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

O rascunho, a biblioteca de roteiros, os ajustes e os takes são guardados no espaço privado do aplicativo. Visitante e cada conta têm bibliotecas separadas. Entrar ou sair não importa automaticamente os roteiros do visitante para a conta, nem mistura seus arquivos. A troca de servidor encerra o acesso atual e retorna à área de visitante.

Os originais MP4 permanecem no espaço privado do aplicativo. **Desinstalar o aplicativo ou limpar seus dados apaga essas cópias locais.** Use **Salvar** para guardar uma cópia no destino escolhido pelo seletor de arquivos do Android. **Compartilhar** abre o seletor de aplicativos do sistema; não há publicação automática em redes sociais.

A biblioteca usa escrita atômica. Takes concluídos que tenham ficado sem índice após uma interrupção podem ser recuperados pelos metadados do MP4. Arquivos incompletos ou ilegíveis são preservados no armazenamento privado, mas não são apresentados como takes concluídos. Se um JSON local estiver corrompido, suas versões brutas são preservadas em uma pasta privada de recuperação antes de uma nova gravação da biblioteca.

## Câmera e teleprompter

### Abertura e rotação

A abertura exibe a marca OpenScript em arte vetorial, com uma breve animação e transição para o estúdio. Ela aparece ao iniciar o aplicativo e não se repete ao girar a tela.

O ícone do aplicativo preserva a marca original, com camadas adaptáveis e uma versão monocromática para os temas do Android. Os arquivos por densidade e a imagem de 512 pixels estão incluídos no projeto. Para regenerá-los a partir de `studio/static/studio/app-icon.svg`, execute `node android/tools/build-icons.cjs` na raiz do repositório, com a biblioteca `sharp` disponível. A pasta `android/branding/` contém a imagem de 512 pixels e a prévia dos formatos.

O aplicativo acompanha a rotação permitida pelo Android. Na horizontal, a navegação fica à esquerda e os controles de gravação à direita. Editor, conta, documentos, reprodução e exportação usam o espaço disponível e oferecem rolagem quando necessário, inclusive com o teclado aberto.

Girar durante um take não o encerra nem zera o contador. O MP4 mantém a orientação escolhida no início da gravação; a próxima gravação adota a orientação atual. Essa regra também se aplica à pausa. A prévia acompanha a tela, e o texto continua fora do vídeo.

Na primeira entrada no estúdio, o aplicativo pede acesso à câmera e ao microfone. Sem câmera autorizada, ainda é possível preparar roteiros. Com o microfone desativado, a gravação sem áudio exige uma confirmação explícita.

O teleprompter aparece sobre a prévia e oferece velocidade, tamanho, ajuste automático do texto à tela, fonte, cor, opacidade de fundo e visibilidade. É possível ensaiar sem gravar. **O texto do teleprompter não é incorporado ao MP4.** Chegar ao fim do texto também não encerra a gravação.

A prévia frontal pode ser espelhada para facilitar o enquadramento. Esse ajuste afeta a visualização; o vídeo é capturado sem espelhamento. A câmera traseira não é espelhada por esse controle.

A captura usa CameraX e uma qualidade disponível no aparelho. Há contagem regressiva, pausa, retomada e encerramento do take. Colocar o aplicativo em segundo plano ou perder o acesso à câmera encerra a captura em andamento. A gravação não é retomada automaticamente ao voltar. Um take interrompido que permaneça reproduzível é mantido para revisão e exportação.

## Exportação e compartilhamento

A aba **Takes** permite reproduzir, compartilhar, excluir a cópia privada e abrir a exportação. É possível salvar ou compartilhar o original sem processá-lo novamente.

O processamento usa Media3 para gerar uma nova cópia MP4 H.264/AAC, quando o codificador do aparelho oferecer esse formato. O original não é modificado. Mantenha o aplicativo aberto durante o processamento; cancelar uma exportação remove sua saída incompleta e preserva o take original.

Se o decodificador padrão falhar, a reprodução e a exportação tentam uma vez os decodificadores de software disponíveis no aparelho. O processamento pode ficar mais lento. Se essa alternativa também falhar, o original continua disponível para salvar e compartilhar.

O catálogo tem 14 opções, incluindo **Personalizado**, e é compartilhado com a web em [`studio/static/studio/export-catalog.json`](../studio/static/studio/export-catalog.json). O Gradle incorpora esse arquivo aos recursos do aplicativo durante a compilação. As opções incluem Instagram, TikTok, YouTube, Facebook, LinkedIn, X, WhatsApp, Pinterest e Kwai.

No perfil personalizado, é possível escolher:

| Ajuste | Opções |
| --- | --- |
| Proporção | 9:16, 4:5, 1:1, 16:9 e 2:3 |
| Resolução solicitada | 360p, 720p e 1080p, usando o lado curto |
| Qualidade | Compacta, equilibrada e alta |
| Quadros por segundo | 24 ou 30 FPS |

A imagem completa é ajustada à proporção escolhida, com preenchimento quando necessário. A resolução fica limitada pela gravação original para evitar ampliação artificial; o aplicativo informa quando essa limitação altera as dimensões previstas. O codificador também pode exigir uma resolução compatível com o aparelho.

As orientações de duração dos perfis são sugestões para publicação. Elas não cortam o vídeo nem interrompem a gravação. A opção **Salvar** usa o seletor de destino do Android, por meio do Storage Access Framework. A saída processada fica no cache até ser salva em um destino escolhido. Cópias temporárias com mais de 24 horas podem ser removidas ao iniciar uma nova exportação; o Android também pode liberar o cache quando necessário.

## Conta e conexão com o servidor

Na aba **Conta**, configure apenas a origem do servidor, por exemplo `https://seu-dominio`, sem acrescentar `/api/v1/`. O cliente adiciona os caminhos da API. A versão de produção aceita HTTPS; não há uma liberação geral de HTTP.

O aplicativo oferece login por e-mail e senha, cadastro, recuperação de senha e saída. O cadastro exige abrir os Termos de Uso e a Política de Privacidade, chegar ao fim de cada documento, confirmar a leitura e marcar os dois aceites separadamente. O link de recuperação recebido por e-mail abre o fluxo web de redefinição de senha.

O token fica cifrado com uma chave do Android Keystore. Ele não é mostrado na interface, e o backup dos dados do aplicativo está desativado. Ao receber `401`, o aplicativo pede novo login e preserva os arquivos locais da conta. Não há renovação automática de token. A saída limpa o acesso local; se o servidor estiver indisponível, o token remoto pode continuar válido até expirar.

A sincronização é explícita: buscar roteiros da conta, enviar o roteiro em edição, aplicar ajustes da conta ou enviar os ajustes do aparelho. Aplicar ajustes da conta substitui as preferências locais dessa área. Conflitos de revisão mantêm a edição local para comparação; o aplicativo não faz mesclagem automática. Vídeos não são enviados ao servidor.

Login nativo com Google não está implementado. O login Google que possa estar habilitado no site não cria esse recurso no aplicativo.

### Desenvolvimento local

Prepare o Django, as migrações e as opções de envio de e-mail seguindo [`docs/native-api.md`](native-api.md). Na raiz do repositório, o servidor pode ser iniciado com:

```powershell
.\venv\Scripts\python.exe manage.py migrate
.\venv\Scripts\python.exe manage.py runserver 127.0.0.1:8000
```

No emulador Android, uma compilação **debug** pode usar `http://10.0.2.2:8000` na aba Conta para acessar esse servidor no computador. A configuração de rede debug limita HTTP a `10.0.2.2`, `127.0.0.1` e `localhost`; endereços HTTP arbitrários da rede local continuam bloqueados.

Como alternativa, com um aparelho ou emulador conectado ao ADB:

```powershell
adb reverse tcp:8000 tcp:8000
```

Depois configure `http://127.0.0.1:8000` no aplicativo debug. Se o ADB não estiver no `PATH`, use o executável em `platform-tools` do SDK. Quando houver vários dispositivos conectados, selecione o alvo com `adb -s SERIAL reverse tcp:8000 tcp:8000`.

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

Validação desta entrega em 19/10/2026: compilação debug, 33 testes de unidade e 9 testes instrumentados passaram, e o lint terminou sem erros. Os testes instrumentados foram executados no emulador API 37, com câmera simulada e entrada de microfone do computador desativada. Eles cobrem captura com trilha de áudio, pausa e retomada, persistência de MP4, exportação H.264/AAC quadrada a 360p, limite de 24 FPS, preservação do original por SHA-256, cancelamento durante preparação, rotação da tela durante a captura sem recriar a Activity ou interromper o take, orientação do take seguinte, recuperação de JSON e contrato HTTP de conta/roteiros/documentos legais com servidor local simulado. O teste HTTP não usa contas reais nem envia e-mails. Essa verificação não substitui testes em aparelhos reais.

Para verificações no emulador, configure a câmera simulada do AVD e mantenha a entrada de microfone do computador desativada. Imagem e áudio desse ambiente não representam uma câmera física. Confira manualmente permissões, gravação silenciosa, pausa e retomada, interrupção ao sair do aplicativo, reprodução do original, exportação, destino salvo e isolamento entre visitante e contas.

## Antes de distribuir

O identificador atual é `com.example.open_script`. Uma publicação exige um pacote próprio, configuração de assinatura de release e gestão segura da chave de assinatura. Também é necessário validar o aplicativo em aparelhos Android reais, incluindo câmera frontal e traseira, sincronismo de áudio, gravações longas, falta de espaço, interrupções e codificadores de fabricantes diferentes.

Esta implementação não inclui distribuição em loja, assinatura de produção, implementação iOS, login Google nativo, upload de vídeos ou publicação automática nas plataformas dos perfis de exportação.
