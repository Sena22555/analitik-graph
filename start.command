#!/bin/bash
# Analitik Graph — запуск одним двойным кликом (macOS) или: bash start.command
cd "$(dirname "$0")"
PORT=8080
# ── ищем Java: системная → JAVA_HOME → JDK, встроенные в VS Code / IntelliJ / Homebrew ──
find_java() {
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; return; fi
  if /usr/libexec/java_home >/dev/null 2>&1; then echo "$(/usr/libexec/java_home)/bin/java"; return; fi
  for j in \
    /opt/homebrew/opt/openjdk*/bin/java /usr/local/opt/openjdk*/bin/java \
    "$HOME"/.vscode/extensions/redhat.java-*/jre/*/bin/java \
    "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
    /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
    "/Applications/IntelliJ IDEA"*.app/Contents/jbr/Contents/Home/bin/java \
    "$HOME"/.sdkman/candidates/java/current/bin/java; do
    [ -x "$j" ] && "$j" -version >/dev/null 2>&1 && { echo "$j"; return; }
  done
}
JAVA="$(find_java)"
if [ -z "$JAVA" ]; then
  echo "❌ Java не найдена. Установите её одной командой:"
  echo "   brew install openjdk@21"
  echo "   (нет Homebrew? https://adoptium.net → скачать Temurin 21 .pkg)"
  exit 1
fi
echo "Java: $JAVA"
# если порт занят старым сервером Analitik Graph — останавливаем его (нужно после обновления jar)
OLD=$(lsof -ti tcp:$PORT -sTCP:LISTEN 2>/dev/null)
if [ -n "$OLD" ]; then
  if ps -o command= -p $OLD | grep -q "analitik-graph\|analytics-graph-engine"; then
    echo "Останавливаю предыдущий сервер (PID $OLD)…"; kill $OLD; sleep 3
    lsof -ti tcp:$PORT -sTCP:LISTEN >/dev/null 2>&1 && kill -9 $OLD 2>/dev/null; sleep 1
  else
    echo "Порт $PORT занят другой программой — закройте её и запустите снова."; exit 1
  fi
fi
if [ -f dist/analitik-graph.jar ]; then
  JAR="dist/analitik-graph.jar"
else
  echo "Готового jar нет — собираю через Maven…"
  (cd analytics-graph-engine && mvn -q -DskipTests package) || exit 1
  JAR="analytics-graph-engine/target/analytics-graph-engine-1.0.0.jar"
fi
( sleep 6; open "http://localhost:$PORT" 2>/dev/null || xdg-open "http://localhost:$PORT" ) &
echo "Analitik Graph → http://localhost:$PORT   (Ctrl+C — остановить)"
exec "$JAVA" -jar "$JAR" --server.port=$PORT --spring.web.resources.cache.cachecontrol.no-cache=true
