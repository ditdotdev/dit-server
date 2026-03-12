FROM ubuntu:24.04

################################################
# Ubuntu software installation
################################################

RUN apt-get -y update && \
    DEBIAN_FRONTEND=noninteractive apt-get -y install \
        ca-certificates \
        curl \
        kmod iproute2 \
        vim rsync sshpass jq \
        openjdk-17-jre-headless \
        zfsutils-linux \
        lsof \
        docker.io \
        tzdata \
        postgresql postgresql-client postgresql-contrib \
        unzip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN ARCH=$(dpkg --print-architecture) && \
    curl -o /usr/local/bin/kubectl -LO "https://storage.googleapis.com/kubernetes-release/release/$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/${ARCH}/kubectl" && \
    chmod 755 /usr/local/bin/kubectl

################################################
# Datadatdat software installation and configuration
################################################

COPY build/libs/datadatdat-server.jar /datadatdat/
COPY src/scripts/* /datadatdat/
COPY assets/* /datadatdat/assets/

RUN /datadatdat/get-userland

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
