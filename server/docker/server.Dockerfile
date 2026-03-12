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

# Download pre-built docker-volume-proxy binary with unified volume naming fix from S3
# TODO: Update CDN to point to datadatdat organization and use CDN URL instead
#
RUN curl -fssL https://datadatdat-maven.s3.amazonaws.com/datadatdat-docker-proxy/docker-volume-proxy -o /datadatdat/docker-volume-proxy
RUN chmod 755 /datadatdat/docker-volume-proxy

RUN echo 'alias psql="psql postgres://postgres:postgres@localhost/datadatdat"' >> /etc/bash.bashrc

WORKDIR /datadatdat
