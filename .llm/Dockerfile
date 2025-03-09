FROM debian

# Note: Run apt commands with proper permissions using sudo
USER root

# Update apt db
RUN apt-get update

# Install OpenJDK 17
RUN apt-get install -y openjdk-17-jdk

# Install git
RUN apt-get install -y git

RUN apt-get clean && \
    rm -rf /var/lib/apt/lists/*
