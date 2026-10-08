FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY api_produccion/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY api_produccion/ .
RUN mkdir -p static/paros static/entregas
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
