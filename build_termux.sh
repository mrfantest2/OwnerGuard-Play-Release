#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
echo "OwnerGuard v1.0.26 Termux native build entry"
echo "Credential coverage: pattern, PIN, and password failed-attempt monitoring"
exec bash "$SCRIPT_DIR/TERMUX_NATIVE_BUILD_INSTALL.sh"
