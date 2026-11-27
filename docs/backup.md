# Backup e restauração (§99)

O Financeiro guarda dados em dois lugares, e os dois precisam de backup:

| O quê | Onde | Como |
|---|---|---|
| Tabelas (cobranças, pagamentos, auditoria, fechamentos…) | PostgreSQL, schema `financeiro` | `scripts/backup.sh` ou `scripts/backup.ps1` |
| Arquivos (comprovantes, notas, extratos OFX, pacotes contábeis) | `ARMAZENAMENTO_TIPO=s3`: bucket `S3_BUCKET`; `local`: pasta `ARMAZENAMENTO_DIR` | Versionamento e ciclo de vida no bucket, ou cópia da pasta |

## Banco

```bash
PGHOST=postgres PGUSER=own_financeiro PGPASSWORD=... PGDATABASE=erp \
BACKUP_DIR=/backups BACKUP_RETENCAO_DIAS=30 BACKUP_TESTAR_RESTAURACAO=1 ./scripts/backup.sh
```

```powershell
$env:PGHOST="localhost"; $env:PGUSER="own_financeiro"; $env:PGPASSWORD="..."; $env:PGDATABASE="erp"
.\scripts\backup.ps1 -Pasta D:\backups -RetencaoDias 30 -TestarRestauracao
```

O script:

1. gera `financeiro-AAAAMMDD-HHMMSS.dump` (`pg_dump --format=custom --schema=financeiro`) e o `.sha256` ao lado;
2. confere que o arquivo é legível (`pg_restore --list`);
3. com o teste ligado, restaura num banco temporário, conta as tabelas e as migrations aplicadas e apaga o banco;
4. apaga os dumps mais antigos que a retenção.

Agende uma vez por dia (cron, Agendador de Tarefas ou um job do orquestrador) e copie a pasta para fora do
servidor do banco. Rode com o teste de restauração pelo menos uma vez por semana. O usuário precisa de
leitura no schema; o teste precisa também de `CREATEDB`.

A tabela `auditoria` só aceita inserção (gatilho + `REVOKE`). Ao restaurar, o gatilho volta junto e
continua impedindo alteração.

## Restaurar

```bash
# 1. pare a API (ou tire o Financeiro do gateway)
# 2. confira o arquivo
sha256sum -c financeiro-20261127-020000.dump.sha256
# 3. troque o schema
psql -d erp -c "DROP SCHEMA financeiro CASCADE"
pg_restore --no-owner --no-privileges --dbname=erp financeiro-20261127-020000.dump
# 4. devolva o dono e os acessos de usr_financeiro (como o infra faz na criação do schema)
# 5. suba a API: o Flyway encontra o histórico restaurado e só aplica migrations mais novas
```

## Arquivos

- **S3 / MinIO:** ligue o versionamento do bucket e uma regra de ciclo de vida para versões antigas
  (por exemplo, 90 dias). Com isso, um arquivo apagado ou sobrescrito pode ser recuperado. Mantenha
  `S3_SSE=true` (criptografia no servidor).
- **Disco local** (só desenvolvimento): copie `ARMAZENAMENTO_DIR` junto com o dump. Os registros de
  `financeiro.documentos` apontam para caminhos relativos a essa pasta.

## Chave de criptografia

Os segredos dos webhooks de saída ficam cifrados com `FINANCEIRO_CHAVE_CRIPTOGRAFIA`. Guarde a chave no
cofre de segredos, fora do backup do banco. Sem ela, um dump restaurado não decifra esses segredos e cada
webhook precisa de um segredo novo (botão "Trocar segredo").
