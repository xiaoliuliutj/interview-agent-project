#!/usr/bin/env sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
INFRASTRUCTURE_DIR="$PROJECT_ROOT/infrastructure"
COMPOSE_FILE="$INFRASTRUCTURE_DIR/docker-compose.yml"
ENV_FILE="$INFRASTRUCTURE_DIR/.env"
PROJECT_NAME=${COMPOSE_PROJECT_NAME:-interview-guide}

usage() {
  cat <<'EOF'
用法：scripts/stop.sh [--volumes] [-h|--help]

默认只停止并移除容器，保留 PostgreSQL、Redis、RabbitMQ 和文件卷。
  --volumes      同时删除该 Compose 项目的命名卷（会删除业务数据）
EOF
}

REMOVE_VOLUMES=0
for argument in "$@"; do
  case "$argument" in
    --volumes) REMOVE_VOLUMES=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知选项：$argument" >&2; usage >&2; exit 2 ;;
  esac
done

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "Docker Engine 或 Docker Compose Plugin 不可用。" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker Engine 未运行。" >&2
  exit 1
fi

cd "$INFRASTRUCTURE_DIR"
DOWN_ARGS="--remove-orphans"
if [ "$REMOVE_VOLUMES" -eq 1 ]; then
  DOWN_ARGS="$DOWN_ARGS --volumes"
fi
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC2086
  docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" down $DOWN_ARGS
else
  # shellcheck disable=SC2086
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" down $DOWN_ARGS
fi
if [ "$REMOVE_VOLUMES" -eq 1 ]; then
  echo "服务已停止，命名卷已删除。"
else
  echo "服务已停止，命名卷已保留。"
fi
