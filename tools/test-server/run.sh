#!/bin/sh
# Builds and starts the test SSH server on 127.0.0.1:2222, with UDP 60000-60009
# for Mosh. From the emulator it's at 10.0.2.2:2222. Stop it with: run.sh stop
cd "$(dirname "$0")"
if [ "$1" = stop ]; then
    docker rm -f whelk-test-sshd
    exit
fi
docker build -q -t whelk-test-sshd . >/dev/null
docker rm -f whelk-test-sshd >/dev/null 2>&1
docker run -d --name whelk-test-sshd -p 127.0.0.1:2222:22 -p 127.0.0.1:60000-60009:60000-60009/udp whelk-test-sshd
