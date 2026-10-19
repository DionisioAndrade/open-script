# OpenScript

Estúdio web de gravação com teleprompter, feito com Django e JavaScript. Cole seu roteiro, veja a câmera ao vivo e leia o texto em uma camada transparente sobre o vídeo.

- Estúdio disponível sem login; entrada e cadastro opcionais em Configurações → Sua conta, com sessão por e-mail e senha e opção de manter o acesso neste dispositivo.
- Recuperação de senha por e-mail e integração preparada para entrada com Google.
- Contagem regressiva de 5 segundos antes de gravar.
- Solicitação de autorização da câmera e do microfone ao abrir o estúdio, com opção de tentar novamente se o acesso for negado.
- Controle de velocidade, fonte, cor e tamanho do roteiro.
- Pausa e retomada da gravação e da leitura; revisão e exportação dos vídeos desta sessão.
- O fim do roteiro interrompe apenas a rolagem do texto. A gravação de vídeo e áudio continua para suas considerações finais, até você escolher Encerrar; o tempo estimado de leitura não limita o vídeo.
- Gravações verticais 9:16, com original em 1080 × 1920 e enquadramento completo da câmera, sem zoom ou recorte acrescentado pelo estúdio.
- Exportação com nome do arquivo, formato, resolução, qualidade e quadros por segundo; progresso e opção de cancelar.
- Perfis de exportação para Instagram, TikTok, YouTube, Facebook, LinkedIn, X, WhatsApp, Pinterest e Kwai, com proporções e resolução ajustáveis.
- Câmera em tela cheia em computadores, tablets e celulares, com roteiro transparente e controles sobrepostos; editor e ajustes abrem em painéis.
- Velocidade e posição da leitura acessíveis na barra sobre a câmera; indicadores de tempo de gravação e leitura restante.
- Roteiro e preferências salvos neste navegador.
- Configurações com idioma do aplicativo: Português (Brasil), English e Español, aplicado na hora e lembrado neste navegador.
- Logo Open Script no estúdio e nas configurações, favicon para a aba do navegador e ícones para a tela inicial do celular.
- Prévia e gravação com processamento sincronizado aos novos quadros da câmera; durante a pausa, o estúdio deixa de redesenhar o vídeo gravado e mantém a câmera ao vivo.

O vídeo e o áudio são capturados pelo navegador. A prévia mostra todo o enquadramento fornecido pela câmera sem ampliar, recortar ou distorcer a imagem. Quando o aparelho permite ajustar o zoom, o estúdio solicita 1×, respeitando os limites da câmera. O campo de visão disponível ainda depende da lente e do navegador. A gravação é sempre vertical 9:16 em 1080 × 1920 e preserva a imagem completa: se a câmera entregar outra proporção, espaços azul-escuros completam o quadro sem cortar suas laterais. Esses espaços também podem aparecer na prévia quando a proporção da tela for diferente da câmera. A resolução capturada não acrescenta detalhes que a câmera não forneceu. A opção de espelhar afeta a prévia. O roteiro sobreposto serve para leitura e **não aparece no vídeo exportado**. O servidor guarda contas e sessões e, quando enviados explicitamente pela API nativa, roteiros e preferências. Na versão web, a captura de vídeo e áudio permanece no navegador, sem upload. Exporte e salve seus vídeos antes de fechar ou atualizar a página: os originais permanecem na memória durante a sessão.

## Aplicativo Android v1.0

O projeto nativo está em [`android/`](android/), com Kotlin e Jetpack Compose: teleprompter, gravação com pausa e retomada, biblioteca local de roteiros e takes, revisão e exportação MP4. Funciona sem conta; login e sincronização explícita usam a API do servidor. Consulte o [guia Android](docs/android-v1.md) para executar, conectar ao servidor e gerar o APK. A implementação iOS ainda não foi iniciada.

## API para aplicativo nativo

O servidor oferece uma API JSON versionada em `/api/v1/` para aplicativos Android/iOS com telas próprias: login/cadastro por token, recuperação de acesso, conta, roteiros e preferências por usuário, documentos legais e perfis de exportação. A [documentação de integração](docs/native-api.md) inclui caminhos, exemplos, revisões para evitar sobrescrever edições e limites. O [contrato OpenAPI](api/openapi.json) também está disponível em `/api/v1/openapi.json`.

Os dados enviados explicitamente pelo app podem ser guardados na conta; o estúdio web continua usando armazenamento local e não sincroniza automaticamente. A câmera, gravação, exportação e os takes continuam sob responsabilidade do dispositivo. A API não recebe vídeos. Aplique as migrações antes de usar as novas rotas. `OPENSCRIPT_API_TOKEN_DAYS` configura a validade do acesso entre 1 e 30 dias, com padrão de 30; cookies web não autenticam a API. A integração Google nativa ainda não está implementada.

Os testes da API são executados com `python manage.py test accounts studio api`, incluindo isolamento entre contas, expiração/revogação de tokens, CSRF da web, limites e conflitos de revisão. A versão anterior dos documentos está preservada em `docs/legal/legal_content-2026-10-12-before-native-api.py`.

## Executar no Windows

Requer Python 3.12 ou superior.

```powershell
python -m venv venv
.\venv\Scripts\python.exe -m pip install -r requirements.txt
.\venv\Scripts\python.exe manage.py migrate
.\venv\Scripts\python.exe manage.py runserver
```

Abra http://127.0.0.1:8000 para usar o estúdio sem login. A câmera e o microfone são solicitados ao abrir o estúdio. Para entrar ou criar uma conta, abra Configurações → Sua conta. Se o ambiente `venv` já existir, comece pela instalação das dependências e execute as migrações. O banco SQLite `db.sqlite3`, ignorado pelo Git, guarda contas e sessões.

## Conta e acesso

O estúdio abre diretamente e permite escrever, gravar e exportar sem conta. Em Configurações → Sua conta, Entrar e Criar conta ficam disponíveis para quem quiser se identificar. O cadastro pede nome, e-mail, senha e confirmação. E-mails são únicos sem diferenciar maiúsculas; senhas passam pelos validadores e pelo mecanismo de hash do Django. Sem Manter conectado, a sessão expira ao fechar o navegador; com essa opção, dura até 30 dias. Sair da conta retorna ao estúdio como visitante.

O cadastro também exige leitura e aceite separado dos Termos de Uso e da Política de Privacidade. Abra cada documento, role até o final, confirme Li este documento e marque sua confirmação. Nenhuma opção vem marcada. O servidor exige os dois documentos abertos e confirmados na mesma sessão, as duas caixas de aceite e as versões atuais; validar apenas a tela não basta. O registro de cadastro guarda data e versões na tabela `LegalAcceptance`, sem adicionar IP ou dispositivo ao registro. Contas anteriores não recebem um aceite inventado.

### Recuperar senha

Na tela Entrar, use Esqueci minha senha e informe o e-mail da conta. O link permite definir uma nova senha, expira em uma hora e só pode ser usado uma vez. A resposta não revela se o endereço está cadastrado. Senhas fracas são rejeitadas e a redefinição invalida sessões anteriores sem entrar automaticamente. Contas criadas exclusivamente pelo Google usam Continuar com Google; a recuperação local não altera a senha do Google.

O Django lê `.env` automaticamente; variáveis já definidas no ambiente têm prioridade. O envio usa `EMAIL_HOST`, `EMAIL_PORT`, `EMAIL_USE_TLS` ou `EMAIL_USE_SSL`, `EMAIL_HOST_USER`, `EMAIL_HOST_PASSWORD` e `DEFAULT_FROM_EMAIL`. Os dados existentes foram preservados. `.env.example` lista os campos sem incluir credenciais. Há limite de 5 pedidos de recuperação por IP em 15 minutos e 20 tentativas inválidas de confirmação. Falhas de envio não exibem detalhes de contas ou credenciais.

`OPENSCRIPT_PUBLIC_URL` define a origem dos links de recuperação e o retorno do Google. Ela é fixa: cabeçalhos enviados pelo visitante não podem trocar o domínio dos links. Para o túnel atual, use `https://prefix-jalapeno-footboard.ngrok-free.dev`. Ao trocar o túnel, atualize essa origem e `DJANGO_CSRF_TRUSTED_ORIGINS`; para testes locais, é permitido `http://127.0.0.1:8000`. A conexão SMTP foi autenticada sem enviar mensagens reais; os testes de redefinição usam uma caixa de e-mail em memória.

### Entrada com Google

A integração usa django-allauth e permanece desativada até preencher `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET` na `.env`, conforme a escolha de configurar depois. O botão continua visível com indicação de indisponibilidade. Não é necessário configurar Google para usar o estúdio ou o login por e-mail.

No [Google Cloud](https://console.cloud.google.com/auth/clients), crie um cliente OAuth do tipo Aplicação Web e cadastre o retorno `https://prefix-jalapeno-footboard.ngrok-free.dev/accounts/google/login/callback/`. O retorno deve corresponder exatamente à origem configurada. Preencha a tela de consentimento e os usuários de teste quando o aplicativo estiver em teste. Salve os dois valores na `.env` e reinicie o servidor. Consulte a [documentação oficial de OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect) para registrar o aplicativo.

O Google é iniciado por POST com CSRF e solicita somente identificação básica: `openid`, `profile` e `email`. A biblioteca valida o retorno, o estado da autenticação e os dados da identidade; exige e-mail confirmado e usa o identificador estável da conta Google. Tokens não são guardados de forma permanente nem incluídos no cadastro pendente. Um cadastro novo só é salvo depois da leitura e dos dois aceites, junto ao registro de data e versões.

Uma conta local com e-mail igual não é vinculada automaticamente. Entre com sua senha e use Configurações → Sua conta → Conectar Google; a conta Google deve ter o mesmo e-mail. Entrar, conectar ou sair respeita as proteções para gravações em andamento e vídeos ainda não exportados. A associação mantém o mesmo identificador do OpenScript e os mesmos dados locais. A configuração não inclui acesso ao Gmail, Drive, contatos ou vídeos.

Os documentos são públicos em `/accounts/terms/` e `/accounts/privacy/`, em português, inglês e espanhol. Os links também aparecem no login, cadastro e Configurações → Sua conta. Ler no cadastro mantém nome, e-mail e senhas no formulário, sem colocar senhas na sessão. Sem JavaScript, leia e confirme os documentos pelas páginas completas antes de preencher o cadastro. A interface verifica que a rolagem chegou ao fim; isso não comprova a compreensão humana do texto.

Os textos em `accounts/legal_content.py` refletem a versão de testes: conta opcional e sessões no servidor, roteiro e preferências de visitante ou conta no navegador, vídeo e áudio locais, cookies necessários, logs e conexão externa via ngrok. O aceite registra ciência dos documentos e não é consentimento genérico para publicidade, rastreamento ou qualquer uso dos dados. Referências: [LGPD](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm), [direitos do titular](https://normas.leg.br/?urn=urn%3Alex%3Abr%3Afederal%3Alei%3A2018-08-14%3B13709%21art18), [bases de tratamento — ANPD](https://www.gov.br/anpd/pt-br/acesso-a-informacao/perguntas-frequentes), [privacidade](https://ngrok.com/privacy), [termos](https://ngrok.com/tos) e [tratamento de dados do ngrok](https://ngrok.com/dpa).

Por escolha do responsável pelo projeto, nome e e-mail de contato ficaram **para preencher depois**, sinalizados nos documentos como pendentes nesta versão de teste. Configure `OPENSCRIPT_LEGAL_RESPONSIBLE` e `OPENSCRIPT_PRIVACY_EMAIL` com os dados reais e revise os textos para a operação efetiva. Não publique essa minuta como uma política final sem completar esses dados. Ao mudar texto, finalidades, dados tratados ou contato, preserve uma cópia da versão anterior e atualize `TERMS_VERSION` e/ou `PRIVACY_VERSION`; o servidor rejeita versões antigas no novo cadastro.

Em Configurações → Sua conta, visitantes encontram os botões Entrar e Criar conta em destaque; quem está conectado vê nome/e-mail e Sair da conta. Durante a preparação, gravação ou exportação, essas ações ficam indisponíveis até cancelar ou encerrar a operação. Vídeos ainda não salvos exigem confirmação antes de navegar para o login, cadastro ou sair. Se a conta mudar em outra aba ou a sessão expirar, a aba antiga informa isso e bloqueia novas gravações, mantendo os takes disponíveis para exportação e a captura em andamento até a ação manual de Encerrar. Você pode escolher Continuar sem login para abrir o estúdio de visitante, respeitando a mesma proteção para operações em andamento e vídeos não salvos.

Roteiro, aparência e idioma do visitante ficam separados dos dados de cada conta **neste navegador**; ainda não são sincronizados entre dispositivos. Entrar ou sair de uma conta não transfere automaticamente o roteiro nem os ajustes de leitura e câmera entre essas áreas. Uma conta sem idioma próprio salvo pode usar a escolha atual do visitante; a preferência de idioma já salva para a conta prevalece. Os vídeos continuam na memória da página e devem ser exportados antes de entrar, sair ou criar uma conta. As páginas de login e cadastro não abrem os dispositivos.

Os formulários de cadastro, login e saída usam proteção CSRF. O login tem limite de 10 falhas por IP/e-mail em 15 minutos; o cadastro, 5 tentativas por IP no mesmo intervalo. O cache local atende o servidor de desenvolvimento; para usar vários processos, configure um cache compartilhado. O endereço atual do ngrok está em `CSRF_TRUSTED_ORIGINS`; ao trocar de domínio, configure `DJANGO_CSRF_TRUSTED_ORIGINS` com as origens HTTPS separadas por vírgula. O proxy deve definir `X-Forwarded-Proto` corretamente.

A identidade visual está em `studio/static/studio/`: logo em SVG, favicon em SVG/ICO/PNG e ícones PNG para Android e iPhone. O manifesto e as tags do aplicativo permitem usar esses ícones ao adicionar a página à tela inicial pelo navegador. Isso não adiciona funcionamento offline. O arquivo `logo-original.svg` preserva o vetor fornecido; `studio/tools/build-icons.cjs` regenera os recortes e ícones usando Node.js e a biblioteca `sharp`.

## Usar o estúdio

1. Abra o estúdio, com ou sem conta, e permita o acesso à câmera e ao microfone na solicitação do navegador. Se o acesso estiver bloqueado, libere a permissão no navegador e use Tentar novamente. Autorizar os dispositivos abre a prévia; a gravação só começa quando você escolher Gravar.
2. Use Editar roteiro para escrever ou colar o texto. Abra Configurações pelo botão de ajustes na barra inferior para escolher idioma, fonte, cor, tamanho e fundo; feche o painel para voltar à câmera.
3. Confira o enquadramento completo da câmera para a gravação 9:16. A barra sobre o vídeo permite ajustar a velocidade, avançar no texto e ensaiar a leitura; o botão da câmera permite desligar e reativar os dispositivos.
4. Inicie a gravação; a contagem de 5 segundos permite se preparar.
5. Pause quando precisar. Quando o roteiro terminar, continue falando pelo tempo que quiser; toque em Encerrar para concluir a gravação.
6. Use Gravações para abrir os vídeos desta sessão e revisar os takes. Escolha Exportar e selecione o Destino do vídeo para aplicar os ajustes sugeridos. Você também pode escolher Personalizado e ajustar nome, formato, proporção, resolução, qualidade e quadros por segundo.
7. Toque em Exportar vídeo e aguarde o processamento; o arquivo preparado é salvo pelo navegador. Se ele bloquear o salvamento automático, use Salvar arquivo. Cancelar exportação interrompe o processamento e preserva o original para tentar novamente.

As opções Leve, Equilibrada e Alta ajustam a taxa de dados usada na codificação; 24 e 30 fps ajustam a frequência dos quadros. O tamanho apresentado é uma estimativa. As proporções disponíveis são 9:16, 4:5, 1:1, 16:9 e 2:3, com o lado menor em 360, 720 ou 1080 pixels. A exportação preserva o áudio e toda a imagem, preenchendo em azul-escuro os espaços necessários para outra proporção. O original continua disponível depois de cada exportação, permitindo criar versões diferentes do mesmo take.

## Exportar para redes sociais

Em Gravações → Exportar → Destino do vídeo, selecione um perfil. Os ajustes sugeridos são escolhas do OpenScript, e podem ser personalizados:

| Destino | Proporção | Resolução sugerida |
| --- | --- | --- |
| Instagram Reels e Stories, TikTok, YouTube Shorts, Facebook Reels, Pinterest e Kwai | 9:16 | 1080 × 1920 |
| Instagram Feed e Facebook Feed | 4:5 | 1080 × 1350 |
| LinkedIn Feed | 1:1 | 1080 × 1080 |
| YouTube Vídeos e X | 16:9 | 1920 × 1080 |
| WhatsApp Status | 9:16 | 720 × 1280 |

Todos os perfis começam com qualidade Equilibrada e 30 fps. O app prefere MP4. Em navegadores sem esse codificador, YouTube e TikTok permitem a alternativa WebM (no TikTok, pelo TikTok Studio na web); os outros perfis explicam a indisponibilidade e oferecem Personalizado ou outro navegador com MP4. As opções que excedem a resolução do original ficam desativadas. A taxa de dados acompanha a quantidade de pixels para evitar arquivos desnecessariamente grandes em formatos quadrados ou 4:5.

Alterar um ajuste de vídeo passa a seleção para Personalizado, mantendo os valores escolhidos. Alterar apenas o nome mantém o destino. A prévia acompanha a proporção selecionada. Você pode exportar o mesmo take para vários destinos, preservando o original completo.

Os avisos de duração e tamanho orientam a publicação; **nunca cortam o vídeo nem encerram a gravação**. As regras podem variar conforme a conta e o caminho de envio. Os perfis não enviam o arquivo a uma rede social.

Referências oficiais consultadas em 12/10/2026: [YouTube Shorts](https://support.google.com/youtube/answer/15424877?hl=en-GB) (até 3 minutos), [YouTube vídeos longos](https://support.google.com/youtube/answer/71673?hl=en) (verificação acima de 15 minutos), [codificação do YouTube](https://support.google.com/youtube/answer/1722171?hl=en-GB), [formatos aceitos pelo YouTube](https://support.google.com/youtube/troubleshooter/2888402?hl=en-GB), [LinkedIn](https://www.linkedin.com/help/linkedin/answer/a548372?iframe=yes) (15 minutos/5 GB), [X](https://help.x.com/en/using-x/x-videos) (140 segundos/512 MB para contas comuns; Premium difere), [WhatsApp Status](https://faq.whatsapp.com/643144237275579/?cms_platform=iphone&locale=pt_BR) (90 segundos), [Pinterest](https://help.pinterest.com/en/article/review-pin-specs) (4 segundos–5 minutos, recomenda 9:16), [TikTok Studio](https://support.tiktok.com/en/using-tiktok/creating-videos/creator-tools-on-tiktok?lang=nl) (MP4/WebM) e [Facebook/Meta](https://about.fb.com/news/2025/06/making-it-easier-create-videos-facebook) (novo fluxo de Reels sem restrição de orientação ou duração). Instagram e Kwai usam sugestões de enquadramento, sem impor limites numéricos não confirmados.

Em Configurações, a seleção de idioma traduz os controles, janelas e mensagens do aplicativo imediatamente, sem recarregar a página. Seu roteiro, título e nome de arquivo personalizados mantêm o texto que você escreveu. A troca de idioma preserva a gravação em andamento e os vídeos da sessão. Se o navegador bloquear o armazenamento local, a escolha funciona na página atual, mas pode não ser lembrada ao reabrir.

MP4 e WebM aparecem apenas quando o navegador consegue codificá-los. O formato padrão é MP4 quando disponível, ou WebM. A opção Original salva o arquivo capturado sem converter e desativa os ajustes de resolução, qualidade e quadros por segundo. A extensão corresponde ao formato real do arquivo. A conversão ocorre no próprio dispositivo, usando as APIs de vídeo e áudio do navegador, e pode levar aproximadamente a duração do take; mantenha a aba aberta e em primeiro plano. Vídeos longos ou dispositivos com pouca memória podem exigir mais tempo ou a opção Original.

O navegador pode lembrar uma autorização anterior e abrir a prévia sem repetir a pergunta. Se você já tiver negado o acesso, pode ser necessário alterar as permissões do site antes de tentar novamente.

O aplicativo aceita HTTP e tenta acessar a câmera e o microfone sempre que o navegador disponibiliza `getUserMedia`, sem bloquear por tipo de conexão. O próprio navegador normalmente libera essa API apenas em `localhost` ou HTTPS; testes por um IP em HTTP dependem de uma exceção para essa origem no navegador de testes. A gravação também exige suporte a `MediaRecorder`. Se não houver microfone, o estúdio permite gravar vídeo sem áudio e informa isso antes de começar. Permissões negadas ou dispositivos ocupados precisam ser resolvidos antes da captura. Mantenha a aba aberta e em primeiro plano durante a gravação; mudanças de aba e bloqueio de tela podem interromper a captura, especialmente em dispositivos móveis. O espaço disponível na memória do dispositivo limita a duração dos vídeos.

Para abrir no celular, use o endereço do computador na rede e um navegador de testes que permita acesso aos dispositivos nessa origem HTTP, ou disponibilize o projeto por **HTTPS**. O endereço `127.0.0.1` no telefone aponta para o próprio telefone. Remover a verificação do aplicativo não altera a política de acesso aos dispositivos imposta pelo navegador. O modo de tela cheia funciona dentro da página quando o navegador não oferece a API de tela cheia, mantendo o roteiro e os controles disponíveis.

Se a página sair de primeiro plano, a gravação pausa e exige tocar em Retomar ao voltar; uma contagem regressiva em andamento é cancelada. O estúdio solicita que a tela permaneça ligada quando o navegador disponibiliza esse recurso.

## Desempenho

A rolagem do texto acompanha a atualização da tela, enquanto os controles de posição são atualizados até 10 vezes por segundo e o relógio uma vez por segundo. Textos e estilos já iguais não são reescritos. A presença de texto e a velocidade são guardadas ao editar o roteiro ou ajustar os controles, evitando percorrer o roteiro inteiro a cada quadro. Ao terminar a leitura, o aplicativo interrompe a animação do texto e mantém o relógio e a captura independentes.

A gravação e a exportação usam `frame-pump.js` para desenhar novos quadros do vídeo, com limite no FPS escolhido e uma alternativa por temporizador nos navegadores sem callbacks de vídeo. O quadro vertical mantém a mesma resolução de 1080 × 1920, a imagem inteira da câmera, o áudio e a qualidade de codificação. O fundo de preenchimento é redesenhado quando o enquadramento muda. Pausar a gravação interrompe esses desenhos até retomar; encerrar cancela os callbacks e libera os recursos. A exportação continua ocorrendo no próprio dispositivo e em tempo de reprodução do vídeo.

Na comparação com Edge em tamanho de celular, roteiro de 100 KiB e câmera sintética de 30 fps, a gravação com rolagem apresentou 99,2% menos escritas de texto na interface, 97,3% menos cálculos de layout e 87,2% menos atualizações dos controles de posição. O canvas recebeu todos os 90 quadros da câmera nas janelas de aproximadamente 3 segundos, inclusive após o fim do roteiro. O arquivo nativo em 1080 × 1920, com áudio, foi reproduzido com aproximadamente 30 fps. Durante a pausa não houve novos desenhos nem preenchimentos do canvas. Esses números medem o trabalho repetido evitado nesse cenário; não significam redução equivalente de CPU ou bateria em todos os aparelhos.

## Acesso externo para testes com ngrok

O agente do ngrok está instalado localmente em `.local-tools/ngrok/ngrok.exe`, fora do controle de versão. Conecte sua conta uma vez com o authtoken disponível no [painel do ngrok](https://dashboard.ngrok.com/get-started/your-authtoken):

```powershell
.\.local-tools\ngrok\ngrok.exe config add-authtoken "SEU_AUTHTOKEN"
```

O token fica na configuração local do ngrok, fora deste projeto. Com o Django funcionando na porta 8000, abra outro terminal no projeto e execute:

```powershell
.\.local-tools\ngrok\ngrok.exe http http://127.0.0.1:8000
```

Abra no celular o link HTTPS apresentado pelo ngrok. Mantenha o Django e o túnel em execução durante os testes. Para encerrar o acesso externo, pare o ngrok com Ctrl+C. A configuração atual do projeto já aceita o endereço público do túnel.

## Verificar o projeto

```powershell
.\venv\Scripts\python.exe manage.py check
.\venv\Scripts\python.exe manage.py test
```

Os testes de navegador usam um servidor separado em `127.0.0.1:8001`, com contas descartáveis. Playwright precisa estar disponível no Node.js e Microsoft Edge instalado no Windows. Prepare o banco de teste:

```powershell
.\venv\Scripts\python.exe tests\prepare-auth.py
$env:DJANGO_DATABASE_PATH = (Join-Path (Get-Location) 'tests/screenshots/login-test.sqlite3')
.\venv\Scripts\python.exe manage.py runserver 127.0.0.1:8001
```

Em outro terminal, execute:

```powershell
node tests/browser.cjs
node tests/login.cjs
```

`tests/prepare-auth.py` aceita apenas o banco de teste em `tests/screenshots/login-test.sqlite3` e cria uma conta exclusiva para regressões. O helper de navegador autentica pelos formulários normais, sem rotas de exceção. Não prepare essa conta no banco principal. `tests/login.cjs` verifica cadastro, login, saída, idioma, celulares, senhas inválidas, contas com roteiros separados, sessão entre abas e preservação de um take ao perder o acesso. Reinicie o servidor de teste entre repetições desse teste, pois o limite real de cadastros continua ativo.

`node tests/consent.cjs` confere as páginas públicas, leitura e aceite separados, preservação dos campos, falhas de rede, idiomas, modal mobile, rejeição de tentativas de contornar a tela e cadastro sem JavaScript. Reinicie o servidor isolado entre `consent.cjs` e `login.cjs`, pois ambos consomem o limite real de cadastros. Os testes Python cobrem versões atuais, campos obrigatórios, recibos de leitura na sessão, CSRF e criação atômica da conta com seu registro de aceite.

`node tests/guest.cjs` verifica gravação e exportação sem login, pausa e retomada, encerramento manual após o fim do roteiro, entrada em destaque nas configurações, proteção contra perda de vídeos ao entrar/criar conta e isolamento entre dados do visitante e da conta. Usa somente a conta semeada no banco isolado, sem criar contas adicionais.

`node tests/auth-recovery.cjs` verifica as páginas e controles de recuperação e Google em celulares e nos três idiomas, sem enviar e-mails ou acessar o Google. `accounts.test_password_reset` testa os links e senhas com caixa em memória; `accounts.test_google` testa OAuth com respostas simuladas e rejeição de identidades/retornos inválidos. Nunca use credenciais reais ou envie e-mails como parte das regressões automatizadas.

O teste usa câmera e microfone simulados, verifica a solicitação automática de ambos os dispositivos, negação e nova tentativa de autorização, enquadramento completo sem zoom, original vertical decodificado em 1080 × 1920, áudio, pausa, preferências e sincronização dos controles de velocidade e posição. Uma fonte horizontal com três faixas coloridas confirma que as laterais são preservadas e que a imagem cabe proporcionalmente no quadro vertical. Exporta arquivos reais em 720 × 1280 e 360 × 640, decodifica os resultados e confere enquadramento, áudio, nome e formato; verifica quadros por segundo e taxa de dados do codificador, cancelamento com preservação do original e exportação Original com os mesmos bytes da captura. Confere a janela de exportação, prévia proporcional sem recorte, texto transparente, painéis e controles visíveis em 320 e 390 pixels, celular na horizontal, tablet e computador. Também verifica tela cheia nativa e sua alternativa dentro da página, e salva imagens em `tests/screenshots`. Para outro navegador Chromium ou endereço do servidor, defina `TEST_BROWSER_PATH` e `TEST_BASE_URL` antes de executar. A verificação móvel emula tamanhos de tela no Edge; não substitui testar permissões, gravação e exportação em aparelhos Android e iPhone reais. As imagens e os vídeos de teste são ignorados pelo Git.

Para conferir somente layout, painéis e acesso aos dispositivos após uma alteração visual, use `node tests/browser.cjs --layout-only`; esse modo não repete a gravação e a exportação.

Para conferir somente o acesso à câmera e ao microfone, o ajuste opcional de zoom e a alternativa para dispositivos que não oferecem enquadramento nativo, use `node tests/browser.cjs --media-access-only`.

Para conferir somente idiomas, use `node tests/browser.cjs --language-only`. Esse modo verifica troca imediata entre português, inglês e espanhol, persistência, mensagens de permissão e exportação, preservação do roteiro e preferências, configurações acessíveis em celulares e continuidade de uma gravação curta ao mudar o idioma. Também confere as traduções dos textos marcados e modelos de mensagens dinâmicas, além da seleção de idioma com armazenamento local indisponível; não repete a conversão dos vídeos.

Para conferir a continuidade após o fim do roteiro, use `node tests/recording-end.cjs`. Esse teste usa captura real com câmera e microfone simulados para verificar o fim da rolagem, avanço até 100%, pausa e retomada após o texto terminar, roteiro curto sem rolagem e roteiro vazio. Confere que o gravador e o relógio continuam até a ação de Encerrar e que o vídeo salvo preserva áudio e imagem após o texto.

Para conferir o agendamento dos quadros, execute `node tests/frame-pump.cjs`; os casos cobrem diferentes FPS, variações nos timestamps, pausa, fallback e liberação de recursos. `node tests/frame-pump-browser.cjs` verifica a alternativa sem callbacks de vídeo com captura, pausa, retomada e reprodução reais, usando fontes sintéticas.

`node tests/export-performance.cjs` verifica o início do encoder, exportação MP4 em 720p, cancelamento e nova tentativa em WebM 360p a 24 fps. Confere áudio, dimensões, enquadramento e liberação dos agendadores, com diagnóstico de eventos quando ocorre uma falha.

`node tests/social-dimensions.cjs` verifica dimensões válidas e limites do codificador, exporta arquivos reais nas cinco proporções com áudio e confirma enquadramento completo e cancelamento. `node tests/social-profiles.cjs` confere a seleção dos destinos, ajustes personalizados, avisos, alternativas de formato, idiomas e layout móvel, além de exportar vídeos quadrados e horizontais pelos perfis.

O comparativo `tests/performance.cjs` mede o trabalho da interface no Edge com um roteiro de 100 KiB, câmera e microfone sintéticos e tamanho de celular. Para registrar uma versão antes de alterações, use `node tests/performance.cjs --freeze-baseline`, depois `node tests/performance.cjs --baseline`. Após otimizar, use `node tests/performance.cjs --current`. Os relatórios ficam em `tests/screenshots/performance-{before,after,comparison}.json`, fora do controle de versão. As contagens de atualizações, layouts e desenhos mostram o trabalho evitado; o tempo de CPU varia com o computador e não representa uma estimativa de bateria ou velocidade em celulares reais.

## Publicar

Defina `DJANGO_DEBUG=false` e `DJANGO_SECRET_KEY` com uma chave privada; restrinja `ALLOWED_HOSTS` aos domínios da aplicação e configure `DJANGO_CSRF_TRUSTED_ORIGINS` com as origens HTTPS permitidas. Execute `manage.py collectstatic` e configure um servidor WSGI/ASGI com HTTPS e serviço dos arquivos estáticos. O servidor de desenvolvimento é destinado ao uso local.
