#!/usr/bin/env bash
# Deploy the Worker. Settings come from environment variables (Cloudflare
# Workers Builds "Build variables", or CI secrets), falling back to the repo's
# gitignored config.env:
#   API_DOMAIN=...        custom domain the API is served on
#   D1_DATABASE_ID=...    id from `npx wrangler d1 create mangavault`
# Applies pending D1 migrations first.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG="../config.env"
if [[ -f "$CONFIG" ]]; then
  # Values already in the environment win over config.env.
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
    [[ "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    value="${value%$'\r'}"; value="${value%\"}"; value="${value#\"}"
    [[ -n "${!key:-}" ]] || export "$key=$value"
  done < "$CONFIG"
fi
: "${API_DOMAIN:?set API_DOMAIN (build variable or config.env)}"
: "${D1_DATABASE_ID:?set D1_DATABASE_ID (build variable or config.env)}"

# A throwaway config with the real database id; never committed.
GENERATED="wrangler.deploy.toml"
trap 'rm -f "$GENERATED"' EXIT
sed "s/^database_id = .*/database_id = \"$D1_DATABASE_ID\"/" wrangler.toml > "$GENERATED"

npx wrangler d1 migrations apply mangavault --remote -c "$GENERATED"
# Sign-in audiences can also come from build variables (they would otherwise
# be reset to the empty [vars] in wrangler.toml on every deploy).
VARS=()
for key in APPLE_AUDIENCES GOOGLE_AUDIENCES; do
  if [[ -n "${!key:-}" ]]; then VARS+=(--var "$key:${!key}"); fi
done

npx wrangler deploy -c "$GENERATED" --domain "$API_DOMAIN" ${VARS[@]+"${VARS[@]}"}
