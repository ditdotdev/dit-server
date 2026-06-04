FROM ubuntu:26.04

################################################
# Ubuntu software installation
################################################

RUN apt-get -y update && \
    DEBIAN_FRONTEND=noninteractive apt-get -y install \
        ca-certificates \
        curl \
        kmod \
        jq sshpass rsync \
        openjdk-17-jre-headless \
        zfsutils-linux \
        lsof netcat-openbsd \
        tzdata \
        postgresql postgresql-client && \
    # Remove PostgreSQL JIT/LLVM (~240MB) - not needed for metadata database
    rm -rf /usr/lib/postgresql/*/lib/llvmjit*.so \
           /usr/lib/postgresql/*/lib/bitcode \
           /usr/lib/*/libLLVM*.so* && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Docker CLI only (not the full docker.io package with containerd/runc)
# Pin version to avoid GitHub API rate limits during docker build (unauthenticated = 60 req/hr)
ARG DOCKER_VERSION=29.4.0
RUN DPKG_ARCH=$(dpkg --print-architecture) && \
    case "$DPKG_ARCH" in amd64) ARCH=x86_64;; arm64) ARCH=aarch64;; *) ARCH=$DPKG_ARCH;; esac && \
    curl -fsSL "https://download.docker.com/linux/static/stable/${ARCH}/docker-${DOCKER_VERSION}.tgz" | \
    tar xz --strip-components=1 -C /usr/local/bin docker/docker && \
    chmod 755 /usr/local/bin/docker

ARG KUBECTL_VERSION=v1.31.0
RUN ARCH=$(dpkg --print-architecture) && \
    curl -o /usr/local/bin/kubectl -LO "https://storage.googleapis.com/kubernetes-release/release/${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl" && \
    chmod 755 /usr/local/bin/kubectl

################################################
# Dit software installation and configuration
################################################

COPY build/libs/dit-server.jar /dit/
COPY src/scripts/* /dit/

# Download pre-built docker-volume-proxy binary from GitHub Releases
RUN --mount=type=secret,id=gh_token \
    GH_TOKEN=$(cat /run/secrets/gh_token) && \
    ASSET_URL=$(curl -sH "Authorization: token $GH_TOKEN" \
      "https://api.github.com/repos/ditdotdev/dit-docker-proxy/releases/latest" | \
      jq -r '.assets[] | select(.name == "docker-volume-proxy") | .url') && \
    curl -fsSL -H "Authorization: token $GH_TOKEN" -H "Accept: application/octet-stream" \
      "$ASSET_URL" -o /dit/docker-volume-proxy && \
    chmod 755 /dit/docker-volume-proxy

RUN echo 'alias psql="psql postgres://postgres:postgres@localhost/dit"' >> /etc/bash.bashrc

WORKDIR /dit
