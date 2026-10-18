#!/usr/bin/env bash
# Deploy the Worker using values from the repo's gitignored config.env:
#   API_DOMAIN=...        custom domain the API is served on
#   D1_DATABASE_ID=...    id from `npx wrangler d1 create mangavault`
# Applies pending D1 migrations first.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG="../config.env"
[[ -f "$CONFIG" ]] || { echo "Missing $CONFIG (copy config.env.example)" >&2; exit 1; }
set -a; source "$CONFIG"; set +a
: "${API_DOMAIN:?set API_DOMAIN in config.env}"
: "${D1_DATABASE_ID:?set D1_DATABASE_ID in config.env}"

# A throwaway config with the real database id; never committed.
GENERATED="wrangler.deploy.toml"
trap 'rm -f "$GENERATED"' EXIT
sed "s/^database_id = .*/database_id = \"$D1_DATABASE_ID\"/" wrangler.toml > "$GENERATED"

npx wrangler d1 migrations apply mangavault --remote -c "$GENERATED"
npx wrangler deploy -c "$GENERATED" --domain "$API_DOMAIN"
