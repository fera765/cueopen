# Fluwy — roda DENTRO do proot (Alpine). Baseado no init-alpine.sh do Acode (MIT).
export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root
export TERM=xterm-256color
export LANG=C.UTF-8
export PS1="\[\e[38;5;209m\]fluwy\[\033[39m\]@sandbox \[\033[36m\]\w \[\033[0m\]\\$ "

INSTALLING=false
while [ $# -gt 0 ]; do
    case "$1" in
        --installing) INSTALLING=true; shift ;;
        --failsafe) shift ;;
        --) shift; break ;;
        *) break ;;
    esac
done

[ -d /linkerconfig ] || mkdir -p /linkerconfig
[ -f /linkerconfig/ld.config.txt ] || touch /linkerconfig/ld.config.txt
[ -s /etc/resolv.conf ] || printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > /etc/resolv.conf
mkdir -p /root/workspace

if [ "$INSTALLING" = true ]; then
    echo "[*] Atualizando índices do apk..."
    apk update
    echo "[*] Instalando pacotes: $FLUWY_PACKAGES"
    apk add $FLUWY_PACKAGES
    if [ -n "$ANDROID_TZ" ] && [ -f "/usr/share/zoneinfo/$ANDROID_TZ" ]; then
        ln -sf "/usr/share/zoneinfo/$ANDROID_TZ" /etc/localtime
    fi
    [ -f /root/.bashrc ] || echo 'cd /root/workspace 2>/dev/null' > /root/.bashrc
    mkdir -p "$PREFIX/.configured"
    echo "[+] Sandbox pronto."
    exit 0
fi

# Comando único (usado pelo agente): executa e sai
if [ $# -gt 0 ]; then
    exec "$@"
fi

# Sem argumentos: sobe o servidor AXS (PTY via HTTP/WebSocket em 127.0.0.1:8767)
echo "$$" > "$PREFIX/pid"
chmod +x "$PREFIX/axs" 2>/dev/null
if command -v bash >/dev/null 2>&1; then
    exec "$PREFIX/axs" -c "bash --rcfile /root/.bashrc -i"
else
    exec "$PREFIX/axs" -c "sh -i"
fi
