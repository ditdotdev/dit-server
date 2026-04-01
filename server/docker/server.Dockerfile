FROM ubuntu:24.04

################################################
# Ubuntu software installation
################################################

RUN apt-get -y update && \
    DEBIAN_FRONTEND=noninteractive apt-get -y install \
        ca-certificates \
        curl \
        kmod \
        jq \
        openjdk-17-jre-headless \
        zfsutils-linux \
        lsof \
        tzdata \
        postgresql postgresql-client && \
    # Remove PostgreSQL JIT/LLVM (~240MB) - not needed for metadata database
    rm -rf /usr/lib/postgresql/*/lib/llvmjit*.so \
           /usr/lib/postgresql/*/lib/bitcode \
           /usr/lib/*/libLLVM*.so* && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Docker CLI only (not the full docker.io package with containerd/runc)
RUN DPKG_ARCH=$(dpkg --print-architecture) && \
    case "$DPKG_ARCH" in amd64) ARCH=x86_64;; arm64) ARCH=aarch64;; *) ARCH=$DPKG_ARCH;; esac && \
    DOCKER_VERSION=$(curl -fsSL https://api.github.com/repos/moby/moby/releases/latest | jq -r .tag_name | sed 's/^docker-v//') && \
    curl -fsSL "https://download.docker.com/linux/static/stable/${ARCH}/docker-${DOCKER_VERSION}.tgz" | \
    tar xz --strip-components=1 -C /usr/local/bin docker/docker && \
    chmod 755 /usr/local/bin/docker

RUN ARCH=$(dpkg --print-architecture) && \
    curl -o /usr/local/bin/kubectl -LO "https://storage.googleapis.com/kubernetes-release/release/$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/${ARCH}/kubectl" && \
    chmod 755 /usr/local/bin/kubectl

################################################
# Datadatdat software installation and configuration
################################################

COPY build/libs/datadatdat-server.jar /datadatdat/
COPY src/scripts/* /datadatdat/

# Download pre-built docker-volume-proxy binary from GitHub Releases
RUN --mount=type=secret,id=gh_token \
    GH_TOKEN=$(cat /run/secrets/gh_token) && \
    ASSET_URL=$(curl -sH "Authorization: token $GH_TOKEN" \
      "https://api.github.com/repos/datadatdat/datadatdat-docker-proxy/releases/latest" | \
      jq -r '.assets[] | select(.name == "docker-volume-proxy") | .url') && \
    curl -fsSL -H "Authorization: token $GH_TOKEN" -H "Accept: application/octet-stream" \
      "$ASSET_URL" -o /datadatdat/docker-volume-proxy && \
    chmod 755 /datadatdat/docker-volume-proxy

RUN echo 'alias psql="psql postgres://postgres:postgres@localhost/datadatdat"' >> /etc/bash.bashrc

WORKDIR /datadatdat
