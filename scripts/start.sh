#!/usr/bin/env sh
set -eu

# One-click deployment entry point. The script deliberately keeps named
# volumes when replacing containers, so a restart does not delete data.
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
INFRASTRUCTURE_DIR="$PROJECT_ROOT/infrastructure"
COMPOSE_FILE="$INFRASTRUCTURE_DIR/docker-compose.yml"
ENV_FILE="$INFRASTRUCTURE_DIR/.env"
EXAMPLE_FILE="$INFRASTRUCTURE_DIR/.env.example"
PROJECT_NAME=${COMPOSE_PROJECT_NAME:-interview-guide}

usage() {
  cat <<'EOF'
用法：scripts/start.sh [选项]

选项：
  --no-build     停止旧容器后直接启动已有镜像
  --pull         构建前拉取基础镜像，并允许 Dockerfile 更新基础镜像
  --rebuild      构建时禁用缓存（依赖下载缓存仍由 BuildKit 管理）
  -h, --help     显示帮助
EOF
}

BUILD=1
PULL=0
NO_CACHE=0
for argument in "$@"; do
  case "$argument" in
    --no-build) BUILD=0 ;;
    --pull) PULL=1 ;;
    --rebuild) NO_CACHE=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知选项：$argument" >&2; usage >&2; exit 2 ;;
  esac
done

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "Docker Engine 或 Docker Compose Plugin 不可用。" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker Engine 未运行，先启动 Docker Desktop/服务后重试。" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  cp "$EXAMPLE_FILE" "$ENV_FILE"
  echo "已创建 infrastructure/.env；请填写密钥、模型和 AGENT_SYSTEM_KNOWLEDGE_BASE_IDS 后重新执行。" >&2
  exit 1
fi

missing=""
for key in POSTGRES_PASSWORD RABBITMQ_PASSWORD MODEL_NAME MODEL_API_KEY AGENT_SYSTEM_KNOWLEDGE_BASE_IDS; do
  value=$(sed -n "s/^${key}[[:space:]]*=[[:space:]]*//p" "$ENV_FILE" | head -n 1 | tr -d '\r' | sed 's/[[:space:]]*$//')
  if [ -z "$value" ] || printf '%s' "$value" | grep -q '^replace-with-'; then
    missing="$missing $key"
  fi
done
if [ -n "$missing" ]; then
  echo "infrastructure/.env 中尚未填写：$missing" >&2
  exit 1
fi

if [ ! -f "$INFRASTRUCTURE_DIR/fonts/NotoSansCJKsc-Regular.otf" ]; then
  echo "警告：未找到 CJK 字体；中文 PDF 导出可能失败。" >&2
fi

export DOCKER_BUILDKIT=${DOCKER_BUILDKIT:-1}
export COMPOSE_DOCKER_CLI_BUILD=${COMPOSE_DOCKER_CLI_BUILD:-1}
cd "$INFRASTRUCTURE_DIR"

compose() {
  docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

compose config --quiet
# Replace containers first, but never remove named volumes. This makes the
# normal command safe for upgrades while ensuring old code is not still running.
compose down --remove-orphans

if [ "$BUILD" -eq 1 ]; then
  if [ "$PULL" -eq 1 ]; then
    compose pull postgres redis-java redis-python rabbitmq
  fi
  build_arguments="--parallel"
  if [ "$PULL" -eq 1 ]; then build_arguments="$build_arguments --pull"; fi
  if [ "$NO_CACHE" -eq 1 ]; then build_arguments="$build_arguments --no-cache"; fi
  # shellcheck disable=SC2086
  compose build $build_arguments
fi

compose up -d --remove-orphans --wait --wait-timeout 180
compose ps
echo "部署完成。前端地址：http://<服务器IP>/"
