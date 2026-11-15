#!/data/data/com.termux/files/usr/bin/sh
# Termux se browser control.
#   export BROWSER_TOKEN=<app me "Token" button se copy kiya hua>
#   sh bctl.sh ping | url | reload | minimize | show
#   sh bctl.sh load https://chatgpt.com
#   sh bctl.sh eval 'document.title'
#   sh bctl.sh click '#send-button'
#   sh bctl.sh text 'h1'
B="http://127.0.0.1:8765"
H="X-Token: ${BROWSER_TOKEN:?BROWSER_TOKEN set karo (export BROWSER_TOKEN=...)}"
cmd="$1"; shift
case "$cmd" in
  ping|url|reload|minimize|show) curl -s -H "$H" "$B/$cmd" ;;
  load|eval|click|text)          curl -s -H "$H" --data-binary "$1" "$B/$cmd" ;;
  *) echo "usage: bctl.sh ping|url|reload|minimize|show|load URL|eval JS|click SEL|text SEL" ;;
esac
echo
