# A throwaway SSH server for trying Whelk on an emulator: user whelk, password
# whelk, with tmux, vim, htop and mosh-server. Only ever bind it to 127.0.0.1.
FROM alpine:3.22
RUN apk add --no-cache openssh mosh-server tmux vim htop bash ncurses less \
 && ssh-keygen -A \
 && adduser -D -s /bin/bash whelk \
 && echo "whelk:whelk" | chpasswd \
 && mkdir -p /home/whelk/.ssh && chown whelk:whelk /home/whelk/.ssh && chmod 700 /home/whelk/.ssh \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/; s/^#\?KbdInteractiveAuthentication.*/KbdInteractiveAuthentication yes/; s/^#\?AllowTcpForwarding.*/AllowTcpForwarding yes/; s/^#\?GatewayPorts.*/GatewayPorts no/' /etc/ssh/sshd_config
ENV LANG=C.UTF-8
EXPOSE 22
CMD ["/usr/sbin/sshd", "-D", "-e"]
