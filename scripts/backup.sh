#!/usr/bin/env bash
# Backup do schema "financeiro" (§99): dump no formato custom, retenção por dias e teste de restauração.
#
#   PGHOST=localhost PGPORT=5432 PGUSER=own_financeiro PGPASSWORD=... PGDATABASE=erp ./scripts/backup.sh
#
# Variáveis opcionais:
#   BACKUP_DIR=./backups          pasta dos arquivos
#   BACKUP_RETENCAO_DIAS=30       apaga dumps mais antigos que isso
#   BACKUP_TESTAR_RESTAURACAO=1   restaura num banco temporário e confere as tabelas (precisa de CREATEDB)
#
# Os documentos (comprovantes, notas, extratos) ficam no armazenamento configurado: com S3, ligue o
# versionamento e a regra de ciclo de vida no bucket; com disco local, copie ARMAZENAMENTO_DIR junto.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENCAO="${BACKUP_RETENCAO_DIAS:-30}"
TESTAR="${BACKUP_TESTAR_RESTAURACAO:-0}"
BANCO="${PGDATABASE:?defina PGDATABASE}"

mkdir -p "$BACKUP_DIR"
ARQUIVO="$BACKUP_DIR/financeiro-$(date +%Y%m%d-%H%M%S).dump"

echo "Gerando $ARQUIVO"
pg_dump --format=custom --compress=9 --schema=financeiro --no-owner --no-privileges --file="$ARQUIVO" "$BANCO"
sha256sum "$ARQUIVO" > "$ARQUIVO.sha256"
pg_restore --list "$ARQUIVO" > /dev/null   # o arquivo é legível

if [ "$TESTAR" = "1" ]; then
  TEMP="financeiro_restore_teste_$$"
  echo "Testando a restauração em $TEMP"
  createdb "$TEMP"
  trap 'dropdb --if-exists "$TEMP"' EXIT
  pg_restore --no-owner --no-privileges --dbname="$TEMP" "$ARQUIVO"
  TABELAS=$(psql -At -d "$TEMP" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'financeiro'")
  MIGRACOES=$(psql -At -d "$TEMP" -c "SELECT count(*) FROM financeiro.flyway_schema_history WHERE success")
  echo "Restauração ok: $TABELAS tabela(s), $MIGRACOES migration(s)"
  [ "$TABELAS" -gt 0 ] || { echo "Restauração sem tabelas" >&2; exit 1; }
fi

echo "Apagando dumps com mais de $RETENCAO dia(s)"
find "$BACKUP_DIR" -name 'financeiro-*.dump*' -type f -mtime "+$RETENCAO" -print -delete
echo "Backup concluído"
