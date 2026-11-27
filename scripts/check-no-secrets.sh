#!/usr/bin/env bash
# Fails if a tracked file looks like a secret: keystores, keys, env files, local config, or
# token-shaped strings. Run by CI on every push; safe to run locally.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

status=0

# 1. File names that must never be committed.
denylist_regex='(^|/)(local\.properties|keystore\.properties|\.env(\..*)?|id_rsa[^/]*|credentials[^/]*\.json|google-services\.json)$|\.(jks|keystore|p12|pem|key|p8)$'
if bad_files=$(git ls-files | grep -E "$denylist_regex"); then
  echo "::error title=Secret file committed::These tracked files match the secrets denylist:"
  echo "$bad_files"
  status=1
fi

# 2. Token-shaped strings in tracked text files (GitHub tokens, Google API keys, private keys).
token_regex='(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{60,}|AIza[0-9A-Za-z_-]{35}|-----BEGIN [A-Z ]*PRIVATE KEY-----'
if hits=$(git grep -nIE "$token_regex" -- . ':!scripts/check-no-secrets.sh'); then
  echo "::error title=Possible secret in source::Token-shaped strings found (values not shown):"
  echo "$hits" | cut -d: -f1,2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "No secrets found in tracked files."
fi
exit "$status"
