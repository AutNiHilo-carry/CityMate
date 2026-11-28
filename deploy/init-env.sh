#!/bin/sh
set -eu
cd "$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
if [ -e .env ] || [ -L .env ]; then
  printf '%s\n' '.env already exists; preserved without replacing credentials.'
  exit 0
fi
command -v openssl >/dev/null 2>&1 || { printf '%s\n' 'openssl is required.' >&2; exit 1; }

generate_secret() {
  if ! secret=$(openssl rand -hex 24) || [ "${#secret}" -ne 48 ]; then
    printf '%s\n' 'Could not generate a credential. No .env was created.' >&2
    return 1
  fi
  case "$secret" in
    *[!0123456789abcdef]*)
      printf '%s\n' 'Credential generator returned invalid output. No .env was created.' >&2
      return 1
      ;;
  esac
  printf '%s\n' "$secret"
}

mysql_root_password=$(generate_secret)
mysql_password=$(generate_secret)
redis_password=$(generate_secret)
monitor_token=$(generate_secret)

umask 077
temporary_env=$(mktemp .env.tmp.XXXXXXXX)
trap 'rm -f -- "$temporary_env"' 0
trap 'exit 1' HUP INT TERM
printf '%s\n' \
  'HMDP_WEB_PORT=8080' \
  "HMDP_MYSQL_ROOT_PASSWORD=$mysql_root_password" \
  "HMDP_MYSQL_PASSWORD=$mysql_password" \
  "HMDP_REDIS_PASSWORD=$redis_password" \
  "HMDP_MONITOR_TOKEN=$monitor_token" \
  'HMDP_ADMIN_IDS=' \
  'HMDP_PAYMENT_MOCK_ENABLED=false' > "$temporary_env"

# Publish the complete file without replacing a configuration created concurrently.
if ! ln -T -- "$temporary_env" .env; then
  if [ -e .env ] || [ -L .env ]; then
    printf '%s\n' '.env already exists; preserved without replacing credentials.'
    exit 0
  fi
  printf '%s\n' 'Could not publish the generated .env.' >&2
  exit 1
fi
printf '%s\n' 'Created private .env. Keep it with the persistent database volumes.'
