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

# In Cloudflare Workers Builds (WORKERS_CI) or other CI, install the Rust
# toolchain and worker-build when missing. Locally, install them yourself.
[[ -f "$HOME/.cargo/env" ]] && source "$HOME/.cargo/env"
if [[ -n "${WORKERS_CI:-}${CI:-}" ]]; then
  if ! command -v cargo >/dev/null; then
    curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
    source "$HOME/.cargo/env"
  fi
  rustup target add wasm32-unknown-unknown
  command -v worker-build >/dev/null || cargo install worker-build@0.8.7 --locked
fi

# A throwaway config with the real database id; never committed.
GENERATED="wrangler.deploy.toml"
trap 'rm -f "$GENERATED"' EXIT
sed "s/^database_id = .*/database_id = \"$D1_DATABASE_ID\"/" wrangler.toml > "$GENERATED"

npx wrangler d1 migrations apply mangavault --remote -c "$GENERATED"
npx wrangler deploy -c "$GENERATED" --domain "$API_DOMAIN"
