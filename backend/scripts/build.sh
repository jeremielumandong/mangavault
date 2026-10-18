#!/usr/bin/env bash
# Wrangler's custom build (see [build] in wrangler.toml). In Cloudflare
# Workers Builds (WORKERS_CI) or other CI, installs the Rust toolchain and
# worker-build when missing; locally, install them yourself (see README).
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f "$HOME/.cargo/env" ]] && source "$HOME/.cargo/env"
if [[ -n "${WORKERS_CI:-}${CI:-}" ]]; then
  # A system cargo without rustup can't add the wasm target, so check rustup.
  if ! command -v rustup >/dev/null; then
    echo "build.sh: installing the Rust toolchain"
    curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
    source "$HOME/.cargo/env"
  fi
  rustup target add wasm32-unknown-unknown
  if ! command -v worker-build >/dev/null; then
    echo "build.sh: installing worker-build"
    cargo install worker-build@0.8.7 --locked
  fi
fi

cd crates/worker && worker-build --release
