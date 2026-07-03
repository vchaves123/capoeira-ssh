#!/bin/sh
# Launches 14bis SSH using a Java runtime already installed on this machine
# (this bundle ships no JRE). Requires Java 21 or newer.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="$SCRIPT_DIR/lib"

if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] Java not found in PATH."
    echo "        Install Java 21+ (e.g. https://adoptium.net) and re-run this script."
    exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F. '{print $1}')"
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ] 2>/dev/null; then
    echo "[ERROR] Java 21+ is required (found: $JAVA_MAJOR)."
    echo "        Install Java 21+ (e.g. https://adoptium.net) and re-run this script."
    exit 1
fi

OS_NAME="$(uname -s)"
ARCH_NAME="$(uname -m)"

case "$ARCH_NAME" in
    x86_64|amd64)  ARCH=x86_64 ;;
    arm64|aarch64) ARCH=aarch64 ;;
    *) echo "[ERROR] Unsupported architecture: $ARCH_NAME"; exit 1 ;;
esac

EXTRA_OPTS=""
case "$OS_NAME" in
    Linux)
        SWT_JAR="$LIB_DIR/swt/org.eclipse.swt.gtk.linux.$ARCH.jar"
        ;;
    Darwin)
        SWT_JAR="$LIB_DIR/swt/org.eclipse.swt.cocoa.macosx.$ARCH.jar"
        # SWT on macOS requires the app's UI thread to be the process's first thread.
        EXTRA_OPTS="-XstartOnFirstThread"
        ;;
    *)
        echo "[ERROR] Unsupported OS: $OS_NAME"
        exit 1
        ;;
esac

if [ ! -f "$SWT_JAR" ]; then
    echo "[ERROR] No SWT build for $OS_NAME/$ARCH_NAME in lib/swt/."
    exit 1
fi

exec java $EXTRA_OPTS -cp "$LIB_DIR/*:$SWT_JAR" br.com.quatorzebis.ssh.Main
