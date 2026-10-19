# API do OpenScript para Android e iOS

Base: `https://seu-dominio/api/v1/`. Durante desenvolvimento local, use `http://127.0.0.1:8000/api/v1/`. No celular, use a origem HTTPS acessível do servidor. Use os caminhos exatamente como documentados, incluindo a barra final. O contrato para ferramentas e geração de clientes está em [`api/openapi.json`](../api/openapi.json), também servido por `GET /api/v1/openapi.json`.

A API atende aplicativos com telas próprias. Django fornece contas, roteiros, preferências e sugestões de exportação. Câmera, microfone, teleprompter, gravação, pausa, armazenamento de takes, exportação e compartilhamento são implementados no aparelho. Não há endpoint que capture, receba ou processe vídeos, nem controle remoto da câmera.

## Requisições e autenticação

Envie `Content-Type: application/json` nas requisições com corpo. A resposta é JSON, exceto a exclusão concluída, que retorna `204` sem corpo. Campos públicos usam `snake_case`; datas de recursos usam ISO 8601 com fuso. Os corpos têm limite de 256 KiB. JSON inválido, chaves duplicadas, números não finitos e caracteres Unicode inválidos são rejeitados.

As rotas privadas exigem `Authorization: Bearer <access_token>`. Cookies de login web não autenticam essas rotas; o app não precisa de cookie ou token CSRF. As páginas e formulários web continuam usando sessão e CSRF. Armazene o token no armazenamento seguro do sistema, como Keychain no iOS ou armazenamento protegido por Android Keystore. Em produção, use HTTPS.

Login e cadastro entregam um token opaco de 43 caracteres, tipo `Bearer`, sua expiração e a conta. O servidor guarda apenas o hash do token. A validade padrão é de 30 dias, configurável por `OPENSCRIPT_API_TOKEN_DAYS` entre 1 e 30. Cada login gera um acesso independente. Logout revoga apenas o token usado; a mudança ou recuperação da senha invalida todos os tokens anteriores. Não há renovação automática: após um `401`, o app solicita novo login. Login Google nativo ainda não está implementado; `config.authentication.native_google_login` informa `false` mesmo quando o Google web estiver configurado.

```http
POST /api/v1/auth/login/
Content-Type: application/json

{"email":"pessoa@example.com","password":"sua-senha"}
```

Resposta `200`:

```json
{
  "access_token": "TOKEN_OPACO_RETORNADO_PELO_SERVIDOR",
  "token_type": "Bearer",
  "expires_at": "2026-11-11T12:00:00+00:00",
  "user": {
    "id": "UUID_ESTAVEL_DA_CONTA",
    "display_name": "Pessoa",
    "email": "pessoa@example.com"
  }
}
```

## Endpoints

Todos os caminhos abaixo são relativos a `/api/v1/`.

| Método | Caminho | Autenticação | Resultado |
| --- | --- | --- | --- |
| GET | `config/` | Pública | Capacidades, limites, versões dos documentos e ajustes padrão |
| GET | `export-profiles/` | Pública | Catálogo compartilhado com a versão web |
| GET | `openapi.json` | Pública | Contrato OpenAPI 3.0.3 |
| GET | `legal/terms/?lang=pt-BR` | Pública | Termos atuais e comprovante de abertura |
| GET | `legal/privacy/?lang=pt-BR` | Pública | Política atual e comprovante de abertura |
| POST | `legal/read/` | Pública | Confirmar leitura e receber comprovante para cadastro |
| POST | `auth/signup/` | Pública | Cadastro com leitura e dois aceites; token; `201` |
| POST | `auth/login/` | Pública | Login por e-mail e senha; token; `200` |
| POST | `auth/password-reset/` | Pública | Solicitação de recuperação; `202` |
| POST | `auth/logout/` | Bearer | Revogar token atual; `200` |
| GET | `me/` | Bearer | Conta em `{"user": {...}}` |
| PATCH | `me/` | Bearer | Alterar apenas `display_name` |
| GET | `scripts/?limit=20&offset=0` | Bearer | Página de roteiros da própria conta |
| POST | `scripts/` | Bearer | Criar roteiro; `201` |
| GET | `scripts/<uuid>/` | Bearer | Consultar roteiro |
| PATCH | `scripts/<uuid>/` | Bearer | Editar com `revision` |
| DELETE | `scripts/<uuid>/` | Bearer | Excluir com corpo `{"revision": 1}`; `204` |
| GET | `preferences/` | Bearer | Preferências; cria padrões com revisão 1 no primeiro acesso |
| PATCH | `preferences/` | Bearer | Atualização parcial com `revision` |

`OPTIONS` informa os métodos permitidos em `Allow`. Não habilita CORS; apps nativos fazem requisições diretamente. Dados de outra conta retornam `404`, sem revelar sua existência. As rotas privadas não aceitam visitantes; o app pode manter roteiros e ajustes de visitante localmente.

## Cadastro e documentos

1. Consulte `config/` e busque os documentos em `legal/terms/` e `legal/privacy/`. Idiomas: `pt-BR`, `en` e `es`; outros valores usam português. Cada resposta contém `document` e `opened_receipt`.
2. Apresente cada documento. Depois que a pessoa confirmar sua leitura, envie `legal/read/` com `kind`, `version`, `opened_receipt` e `confirmed: true`.
3. Guarde o `read_receipt` retornado para cada documento e envie `auth/signup/`. Os dois aceites devem ser explícitos e independentes. Os recibos expiram em 30 minutos; se a versão mudar ou o recibo expirar, busque e apresente o documento novamente. A confirmação não comprova compreensão humana.

```json
{
  "kind": "terms",
  "version": "VERSAO_RETORNADA_PELO_SERVIDOR",
  "opened_receipt": "COMPROVANTE_DE_ABERTURA",
  "confirmed": true
}
```

Corpo do cadastro:

```json
{
  "display_name": "Pessoa",
  "email": "pessoa@example.com",
  "password": "senha-forte",
  "password_confirm": "senha-forte",
  "accept_terms": true,
  "accept_privacy": true,
  "terms_version": "VERSAO_ATUAL_DOS_TERMOS",
  "privacy_version": "VERSAO_ATUAL_DA_POLITICA",
  "terms_receipt": "COMPROVANTE_DE_LEITURA_DOS_TERMOS",
  "privacy_receipt": "COMPROVANTE_DE_LEITURA_DA_POLITICA"
}
```

Cadastro usa os mesmos validadores de senha e unicidade de e-mail da web e registra as versões efetivamente aceitas. Nome: até 100 caracteres; e-mail: até 254; senha: até 128. Login não inventa novos registros de aceite para contas existentes.

## Roteiros e revisões

Criação exige `title` e `content`, ambos textos. É permitido salvar rascunhos vazios. Título: até 100 caracteres. Conteúdo: até 100 KiB em UTF-8; caracteres acentuados e emojis podem ocupar mais de um byte. Limite de 1000 roteiros por conta.

```json
{"title":"Meu primeiro vídeo","content":"Olá! Este é meu roteiro."}
```

Cada roteiro retorna `id`, `title`, `content`, `revision`, `created_at` e `updated_at`. A lista retorna `items`, `total`, `limit` e `offset`, ordenados por atualização mais recente. `limit` vai de 1 a 100; `offset` é inteiro não negativo.

Para editar, envie a última revisão recebida junto aos campos alterados:

```json
{"revision":1,"content":"Texto revisado."}
```

A atualização incrementa a revisão. Se outro aparelho tiver alterado o recurso, a API retorna `409`, código `revision_conflict` e `error.details.current_revision`. Busque a versão atual e resolva a diferença antes de reenviar; repetir a escrita com a revisão nova sem considerar o conteúdo pode substituir a edição da outra pessoa. A exclusão também exige a revisão no corpo JSON.

## Preferências

`GET preferences/` retorna os campos abaixo, `revision` e `updated_at`. Para alterar, envie apenas os campos desejados e a revisão atual. Para restaurar os padrões, envie `config.default_preferences` junto com essa revisão. PATCH antes do primeiro GET pode usar `revision: 1`.

| Campo | Padrão | Valores |
| --- | --- | --- |
| `language` | `pt-BR` | `pt-BR`, `en`, `es` |
| `speed` | 40 | Número de 10 a 100 |
| `font` | `sans` | `sans`, `serif`, `mono` |
| `size` | 36 | Número de 20 a 120 |
| `auto_size` | true | Booleano |
| `opacity` | 0 | Número de 0 a 70 |
| `color` | `#ffffff` | Cor `#RRGGBB` |
| `show_prompt` | true | Booleano |
| `mirror` | true | Booleano |

Os nomes snake_case correspondem às escolhas atuais da interface web; o app aplica esses valores aos próprios controles. Nada é importado automaticamente do `localStorage` web. O envio e a leitura pela API são explícitos. Não há mesclagem automática ou fila offline implementada no servidor.

## Exportação e recuperação de acesso

`export-profiles/` entrega proporções, resoluções, FPS, taxas de vídeo/áudio e perfis. `preferred_formats` são sugestões; o app verifica o suporte do aparelho. As mensagens e rótulos do catálogo estão em português, como as chaves de tradução da versão web. O app pode traduzi-los por ID. Limites de publicação são orientações: nunca devem interromper gravação ou cortar o vídeo. Não há publicação automática em redes sociais.

`auth/password-reset/` aceita `email` e `lang` opcional. Quando o envio estiver habilitado, retorna a mesma resposta para conta elegível ou endereço desconhecido. O e-mail usa a origem pública fixa configurada; o link abre a recuperação web. A nova senha invalida tokens anteriores e não entra automaticamente. O endpoint retorna `503` quando o envio estiver indisponível. Login/cadastro/recuperação reaproveitam os limites de tentativas da web; `429` inclui `Retry-After`.

## Erros e configuração do servidor

```json
{
  "error": {
    "code": "validation_error",
    "message": "Revise os campos informados.",
    "fields": {
      "email": [{"message":"Informe um e-mail válido.","code":"invalid"}]
    }
  }
}
```

Os códigos HTTP principais são `400` para dados inválidos, `401` para acesso inválido, `404` para recurso inexistente/inacessível, `405` para método, `409` para conflito/limite, `413` para corpo grande, `415` para tipo de conteúdo, `429` para tentativas excessivas e `503` para recuperação indisponível. Campos desconhecidos são rejeitados. Respostas usam `Cache-Control: no-store`.

Antes de servir as novas rotas, instale as dependências existentes e aplique as migrações:

```powershell
.\venv\Scripts\python.exe manage.py migrate
.\venv\Scripts\python.exe manage.py runserver
```

Não foi acrescentada dependência Python. Em produção com vários processos, use cache compartilhado para os limites de tentativas. O app nativo, seus controles de captura, banco local de takes e distribuição Android/iOS serão uma implementação separada que consome este contrato.
