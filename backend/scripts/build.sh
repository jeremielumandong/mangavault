#!/usr/bin/env bash
# Wrangler's custom build (see [build] in wrangler.toml). In Cloudflare
# Workers Builds (WORKERS_CI) or other CI, installs the Rust toolchain and
# worker-build when missing; locally, install them yourself (see README).
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f "$HOME/.cargo/env" ]] && source "$HOME/.cargo/env"
if [[ -n "${WORKERS_CI:-}${CI:-}" ]]; then
  if ! command -v cargo >/dev/null; then
    curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
    source "$HOME/.cargo/env"
  fi
  rustup target add wasm32-unknown-unknown
  command -v worker-build >/dev/null || cargo install worker-build@0.8.7 --locked
fi

cd crates/worker && worker-build --release
