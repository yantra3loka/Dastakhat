# The local DSC signing agent.
#
# The image contains the agent, SoftHSM2 (a software token for the development
# installation) and OpenSC (pkcs11-tool).
#
# Build:
#   docker build -t dastakhat-dsc-agent agent/
#
# Run (the port is published on the loopback interface of the host only):
#   docker run --rm -it -p 127.0.0.1:51515:51515 \
#     -v dastakhat-agent-state:/state dastakhat-dsc-agent
#
FROM python:3.12-slim

LABEL org.opencontainers.image.title="Dastakhat DSC signing agent"
LABEL org.opencontainers.image.description="Local PKCS#11 helper that signs with a DSC token"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    AGENT_HOST=0.0.0.0 \
    AGENT_PORT=51515 \
    AGENT_ALLOW_NON_LOOPBACK=true \
    AGENT_STATE_DIR=/state \
    AGENT_TOKEN_LABEL=intern-dsc \
    AGENT_PKCS11_MODULE=/usr/lib/softhsm/libsofthsm2.so \
    SOFTHSM2_CONF=/state/softhsm2.conf

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        softhsm2 \
        opensc \
        openssl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY agent.py ./agent.py
COPY dscagent ./dscagent
COPY scripts ./scripts
RUN chmod +x scripts/*.sh \
    && mkdir -p /state/tokens /state/tls \
    && chmod 700 /state/tokens

VOLUME ["/state"]
EXPOSE 51515

# The agent reads the PIN and the confirmation from the terminal, so the
# container has to be started with "-it".
ENTRYPOINT ["python", "agent.py"]
