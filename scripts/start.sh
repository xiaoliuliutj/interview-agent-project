#!/usr/bin/env sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
INFRASTRUCTURE_DIR="$PROJECT_ROOT/infrastructure"
ENV_FILE="$INFRASTRUCTURE_DIR/.env"
EXAMPLE_FILE="$INFRASTRUCTURE_DIR/.env.example"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose 不可用。请先安装并启动 Docker Engine + Compose Plugin。" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  cp "$EXAMPLE_FILE" "$ENV_FILE"
  echo "已创建 infrastructure/.env。请填写 MODEL_NAME、MODEL_API_KEY，并替换数据库和 RabbitMQ 密码后再次执行本脚本。" >&2
  exit 1
fi

missing=""
for key in POSTGRES_PASSWORD RABBITMQ_PASSWORD MODEL_NAME MODEL_API_KEY; do
  value=$(sed -n "s/^${key}=//p" "$ENV_FILE" | head -n 1 | tr -d '\r')
  if [ -z "$value" ] || printf '%s' "$value" | grep -q '^replace-with-'; then
    missing="$missing $key"
  fi
done
if [ -n "$missing" ]; then
  echo "infrastructure/.env 中尚未填写：$missing" >&2
  exit 1
fi

if [ ! -f "$INFRASTRUCTURE_DIR/fonts/NotoSansCJKsc-Regular.otf" ]; then
  echo "警告：未找到 CJK 字体。服务可启动，但导出中文 PDF 前请放置字体到 infrastructure/fonts/。" >&2
fi

cd "$INFRASTRUCTURE_DIR"
docker compose --env-file .env config --quiet
if [ "${1:-}" = "--no-build" ]; then
  docker compose --env-file .env up -d --remove-orphans
else
  docker compose --env-file .env up -d --build --remove-orphans
fi
docker compose --env-file .env ps
echo "启动命令已完成。前端访问地址：http://<虚拟机IP>/"
