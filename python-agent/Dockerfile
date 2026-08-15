# syntax=docker/dockerfile:1.7

FROM python:3.12-slim
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

ARG PIP_INDEX_URL=
COPY requirements.txt ./
RUN --mount=type=cache,id=interview-guide-pip,target=/root/.cache/pip \
    if [ -n "$PIP_INDEX_URL" ]; then \
      pip install --index-url "$PIP_INDEX_URL" -r requirements.txt; \
    else \
      pip install -r requirements.txt; \
    fi

COPY app ./app
COPY resources ./resources

EXPOSE 8000
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=20 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"
CMD ["uvicorn", "app.api.application:app", "--host", "0.0.0.0", "--port", "8000"]
