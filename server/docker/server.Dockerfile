FROM ubuntu:focal

################################################
# Ubuntu repository configuration
################################################

RUN apt-get -y update --fix-missing

# Tools required for adding repositories
RUN apt-get -y install apt-transport-https \
     ca-certificates \
     curl \
     gnupg2 \
     software-properties-common

# Add docker repository
RUN curl -fsSL https://download.docker.com/linux/$(. /etc/os-release; echo "$ID")/gpg > /tmp/dkey; apt-key add /tmp/dkey
RUN add-apt-repository \
   "deb [arch=amd64] https://download.docker.com/linux/$(. /etc/os-release; echo "$ID") \
   $(lsb_release -cs) \
   stable"

# Note: Using Ubuntu's default PostgreSQL packages instead of PostgreSQL APT repository
# Ubuntu Focal includes PostgreSQL 12 in the default repositories

RUN apt-get -y update --fix-missing

################################################
# Ubuntu software installation
################################################

RUN apt-get -y install kmod iproute2
RUN apt-get -y install vim rsync sshpass jq
RUN apt-get -y install openjdk-17-jre-headless
RUN apt-get -y install zfsutils-linux
RUN apt-get -y install lsof
RUN apt-get -y install docker.io
RUN DEBIAN_FRONTEND=noninteractive apt-get -y install tzdata
RUN apt-get -y install postgresql postgresql-client postgresql-contrib
RUN apt-get -y install unzip

RUN curl -o /usr/local/bin/kubectl -LO https://storage.googleapis.com/kubernetes-release/release/`curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt`/bin/linux/amd64/kubectl
RUN chmod 755 /usr/local/bin/kubectl

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
