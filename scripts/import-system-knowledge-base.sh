#!/usr/bin/env bash
set -euo pipefail

# Imports one or more deployment-owned documents through the real Java upload/index path.
# Required environment variables deliberately have no defaults: the target service and the
# system owner are deployment choices, not application constants.
: "${JAVA_API_BASE_URL:?set JAVA_API_BASE_URL, e.g. http://127.0.0.1}"
: "${SYSTEM_KNOWLEDGE_OWNER_ID:?set SYSTEM_KNOWLEDGE_OWNER_ID}"

if ! command -v curl >/dev/null || ! command -v jq >/dev/null; then
  echo "curl and jq are required" >&2
  exit 2
fi
if [ "$#" -eq 0 ]; then
  echo "usage: JAVA_API_BASE_URL=... SYSTEM_KNOWLEDGE_OWNER_ID=... $0 <document> [document ...]" >&2
  exit 2
fi

uploaded_ids=()
for document in "$@"; do
  if [ ! -f "$document" ]; then
    echo "knowledge-base document not found: $document" >&2
    exit 2
  fi

  response=$(curl --fail-with-body --silent --show-error \
    -H "X-User-Id: ${SYSTEM_KNOWLEDGE_OWNER_ID}" \
    -F "file=@${document}" \
    -F "name=$(basename "$document")" \
    "${JAVA_API_BASE_URL%/}/api/knowledgebase/upload")
  id=$(printf '%s' "$response" | jq -er '.data.knowledgeBase.id | tostring')
  uploaded_ids+=("$id")
  echo "uploaded: $(basename "$document") -> $id"
done

echo "Waiting for vector indexing..."
for id in "${uploaded_ids[@]}"; do
  for _ in $(seq 1 120); do
    response=$(curl --fail-with-body --silent --show-error \
      -H "X-User-Id: ${SYSTEM_KNOWLEDGE_OWNER_ID}" \
      "${JAVA_API_BASE_URL%/}/api/knowledgebase/list")
    status=$(printf '%s' "$response" | jq -er --arg id "$id" \
      '.data[] | select((.id | tostring) == $id) | .vectorStatus' | head -n 1)
    case "$status" in
      COMPLETED)
        echo "indexed: $id"
        break
        ;;
      FAILED)
        printf '%s\n' "$response" >&2
        echo "indexing failed for knowledge base $id" >&2
        exit 1
        ;;
    esac
    sleep 2
  done
  if [ "$status" != "COMPLETED" ]; then
    echo "timed out waiting for knowledge base $id" >&2
    exit 1
  fi
done

ids=$(IFS=,; echo "${uploaded_ids[*]}")
echo "Set AGENT_SYSTEM_KNOWLEDGE_BASE_IDS=${ids} in infrastructure/.env, then restart java-backend."
