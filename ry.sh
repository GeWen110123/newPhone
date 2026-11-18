#!/bin/sh
set -eu

APP_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_DIR/target/phone.jar"

if [ ! -f "$JAR" ]; then
    echo "Build the project with mvn clean package before starting." >&2
    exit 1
fi

# Accept the original startup invocation as well as direct Spring Boot arguments.
case "${1:-}" in
    start) shift ;;
    stop|restart|status)
        echo 'Run the service in the foreground and press Ctrl+C to stop it.' >&2
        exit 2
        ;;
esac

JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
fi

exec "$JAVA" -Duser.timezone=Asia/Shanghai -jar "$JAR" "$@"
