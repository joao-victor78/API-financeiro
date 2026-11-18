# Módulo Financeiro — API v0.1

Implementação da API do módulo Financeiro do sistema integrado de gestão empresarial
(Projeto Integrador ADS — PUC Goiás), desenvolvida como parte prática do artigo
**"Automação de Pagamentos com Internet das Coisas"** (disciplina 2026.2-ADS1263/C01).

O módulo funciona como um nó autônomo da rede de serviços: tem identidade e endereço próprios
(`/api/financeiro`), valida identidade de forma descentralizada (JWT RS256) e troca dados com outros
módulos e com o banco **sem intervenção humana**. Para isso usa um agendador de recorrências, um webhook
Pix, a conciliação OFX automática e eventos assinados enviados aos demais módulos.

## Stack

**Java 17 · Spring Boot 3.5** (Web, Data JPA/Hibernate, Validation, Scheduling) · **PostgreSQL 13+** · Flyway ·
JUnit 5 + MockMvc · springdoc-openapi (Swagger) · jjwt (RS256) · ZXing (QR Code)

As tabelas ficam no schema `financeiro` do banco indicado na `DATABASE_URL` (`postgres://usuario:senha@host:5432/banco`).

## Como rodar

Pré-requisitos: JDK 17+, Maven 3.9+ e PostgreSQL. Crie os bancos uma vez:

```bash
sudo -u postgres psql -c "CREATE USER financeiro WITH PASSWORD 'financeiro'"   # se ainda não existir
sudo -u postgres psql -c "CREATE DATABASE erp OWNER financeiro"
sudo -u postgres psql -c "CREATE DATABASE erp_test OWNER financeiro"
```

Sem PostgreSQL instalado, use `docker compose up -d`.

```bash
cp .env.example .env                       # no Windows: copy .env.example .env
mvn -q compile exec:java@chaves            # DEV: gera par RSA simulando o módulo de Autenticação
mvn spring-boot:run                        # aplica a migration e sobe em http://localhost:3008/api/financeiro
```

- Documentação interativa: `http://localhost:3008/api/financeiro/docs`
- Token de desenvolvimento: `mvn -q compile exec:java@token -Dexec.args="<tenant_uuid>"` (use em `Authorization: Bearer …`)

## Comandos

| Comando | O que faz |
|---|---|
| `mvn test` | 53 testes (20 unitários + 33 de integração com PostgreSQL real) |
| `mvn verify` | Testes + relatório de cobertura em `target/site/jacoco/index.html` |
| `mvn test -Dtest=Demonstracao` | Demonstração narrada do fluxo M2M completo (para apresentação) |
| `mvn test -Dtest=Benchmark` | Mede recorrência, conciliação, webhook Pix e latência; grava `benchmark-resultados.json` |
| `mvn -q compile exec:java@popular` | Preenche a API em execução com dados fictícios (5 clientes, 4 meses de histórico, contas a pagar, OFX, inadimplência) no tenant padrão do login do frontend |
| `mvn -q compile exec:java@pix -Dexec.args="<chave> <valor> [nome] [cidade]"` | Gera um QR Code Pix avulso (terminal + PNG) para testar com uma chave real, sem banco nem servidor |
| `mvn package` → `java -jar target/financeiro-api-0.1.0.jar` | Build de produção |

> Os testes, a demonstração e o benchmark usam a `DATABASE_URL` do `.env` trocando o nome do banco por
> `<nome>_test` (ex.: `erp` → `erp_test`) e **apagam e recriam** o schema `financeiro` desse banco; o banco de
> trabalho não é tocado. Para usar outro banco, defina `TEST_DATABASE_URL`. Por segurança, os testes se recusam a
> apagar um banco cujo nome não termine em `_test`.

## Frontend

A interface do módulo fica em [`frontend/`](frontend/) (Next.js 15), pensada para ser incorporada ao sistema
principal por iframe. Veja [frontend/README.md](frontend/README.md).

```bash
mvn spring-boot:run                              # API em :3008 (nesta pasta)
cd frontend && npm install && npm run dev        # interface em http://localhost:3000
```

O frontend fala com a API só por HTTP. O token fica em cookie httpOnly (`financeiro_token`), que a API aceita
como alternativa ao cabeçalho `Bearer`.

## Rotas

Todas sob `/api/financeiro`, resposta padrão `{ success, data, message, errors }`, JSON em snake_case.

| Grupo | Rotas |
|---|---|
| Health (público) | `GET /health` |
| Contas a receber | `GET/POST /contas-receber`, `GET/PATCH/DELETE /contas-receber/{id}`, `POST /{id}/baixa`, `POST /{id}/estorno` |
| Contas a pagar | `GET/POST /contas-pagar`, `GET/PATCH/DELETE /contas-pagar/{id}`, `POST /{id}/pagamento` |
| Cobranças | `GET/POST /cobrancas`, `GET/PATCH /cobrancas/{id}`, `POST /{id}/aprovar`, `POST /{id}/cancelar`, `GET /{id}/qrcode` (imagem PNG/SVG do Pix) |
| Recorrências | `GET/POST /recorrencias`, `GET/PATCH/DELETE /recorrencias/{id}`, `GET /{id}/previsao`, `POST /{id}/pausar`, `/reativar`, `/gerar` |
| Inadimplência | `GET /inadimplencia`, `GET /inadimplencia/resumo`, `GET/POST /inadimplencia/promessas` |
| Conciliação | `POST /conciliacoes/importar` (OFX, multipart ou JSON), `GET /conciliacoes`, `GET /{id}`, `GET /{id}/transacoes/{tid}/sugestoes`, `POST …/conciliar`, `POST …/ignorar` |
| Webhook Pix | `POST /webhooks/pix` (assinatura HMAC, sem JWT) |
| Eventos | `GET /eventos`, `GET/POST /eventos/assinaturas`, `DELETE /eventos/assinaturas/{id}` |
| Automação | `GET /automacao/status`, `POST /automacao/executar/{job}` |

## Regras técnicas do projeto atendidas

| Regra | Onde |
|---|---|
| id, tenant e auditoria em toda tabela; soft delete | `comum/EntidadeBase.java` + `@SQLRestriction("deleted_at IS NULL")`, migration |
| snake_case e migrations versionadas | `src/main/resources/db/migration/` (Flyway), Jackson `SNAKE_CASE` |
| Sem FK/join entre schemas — só UUID | migration (FKs apenas internas ao schema `financeiro`) |
| Tenant vem só do token | `comum/auth/ValidadorJwt.java` + `fail-on-unknown-properties` (enviar `tenant_id` → 422) |
| Paginação máx. 100 | `comum/Paginado.java` (`@Max(100)` em todo filtro) |
| JWT RS256, token fora do localStorage | `comum/auth/JwtInterceptor.java` (Bearer ou cookie httpOnly `financeiro_token`) |
| Códigos 200/201/204/400/401/403/404/409/422 | `comum/http/TratadorErros.java` + Bean Validation |

## Automações

| Job | Quando | O que faz |
|---|---|---|
| `recorrencias` | diário 00:05 | Emite e aprova as cobranças cujo ciclo chegou (N dias antes do vencimento), gera Pix e conta a receber; recupera ciclos perdidos; idempotente (UNIQUE recorrência+competência, `SKIP LOCKED`) |
| `vencimentos` | diário 00:15 | Marca títulos vencidos e publica `conta_receber.vencida` |
| `promessas` | diário 00:20 | Marca promessas de pagamento quebradas |
| `eventos` | a cada 15 s | Entrega eventos do outbox aos assinantes (POST com HMAC-SHA256, nova tentativa com backoff exponencial, até 8 tentativas) |

## Estrutura

```
src/main/java/br/com/erp/financeiro/
  comum/            auth (JWT), http (resposta/erros), enums, datas, dinheiro, relógio, paginação, entidade base
  config/           .env, banco (DATABASE_URL), JSON, web, OpenAPI
  contasreceber/  contaspagar/  cobrancas/  recorrencias/  inadimplencia/
  conciliacoes/     parser OFX + motor de conciliação
  pix/              BR Code, QR Code e webhook
  eventos/          outbox + assinaturas + despachante
  automacao/        agendador
  health/
  ferramentas/      gerar chaves, emitir token, QR Pix avulso, dados de demonstração
src/main/resources/ application.properties, db/migration (Flyway)
src/test/java/      testes unitários e de integração; ferramentas/ (Demonstracao, Benchmark)
frontend/           interface Next.js (iframe, sessão em cookie httpOnly)
```

Cada módulo segue a mesma divisão: entidade JPA, repositório Spring Data, DTOs (records com Bean Validation),
serviço com as regras de negócio e controller REST.

## Limitações conhecidas (v0.1)

- O Pix "copia e cola" é gerado localmente (padrão EMV/BR Code com txid). A emissão de QR dinâmico em um PSP real e o mTLS do webhook ficam para a integração com o banco escolhido.
- Encargos de atraso (multa 2% + juros 1% a.m.) são calculados na consulta e não gravados no título.
- O isolamento multi-tenant é feito na aplicação (filtro por `tenant_id` em toda consulta); Row-Level Security no Postgres é uma camada extra recomendada.
- A importação do OFX ainda é por upload; buscar o extrato via Open Finance tornaria esse passo também automático.
