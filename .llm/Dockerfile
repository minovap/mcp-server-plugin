FROM debian

# Note: Run apt commands with proper permissions using sudo
USER root

# Update apt db
RUN apt-get update

# Install OpenJDK 17
RUN apt-get install -y openjdk-17-jdk

# Install git
RUN apt-get install -y git

# Install tmux
RUN apt-get install -y tmux

RUN apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN echo "set -g mouse on\n\
set -g terminal-overrides 'xterm*:smcup@:rmcup@'\n\
bind -n WheelUpPane if-shell -F -t = \"#{mouse_any_flag}\" \"send-keys -M\" \"if -Ft= '#{pane_in_mode}' 'send-keys -M' 'copy-mode -e; send-keys -M'\"\n\
bind -n WheelDownPane select-pane -t= \\; send-keys -M\n\
set -g history-limit 10000" > /root/.tmux.conf
