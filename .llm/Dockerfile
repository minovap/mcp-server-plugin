FROM gitpod/workspace-full

# Note: Run apt commands with proper permissions using sudo
USER root

# Install OpenJDK 17
RUN apt-get update && \
    apt-get install -y openjdk-17-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Set JAVA_HOME environment variable
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Add Java binaries to PATH
ENV PATH=$JAVA_HOME/bin:$PATH

# Create a .bashrc that sets JAVA_HOME for interactive sessions
RUN echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> /etc/bash.bashrc && \
    echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/bash.bashrc

# Verify Java installation
RUN java -version
