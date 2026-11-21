#!/usr/bin/env bash
set -euo pipefail
cd /home/user/minecraft-server-template/.claude/worktrees/agent-a71605a46a7f6758a/mods/custom-dimensions-26
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
GSON=$(find /root/.gradle/caches/modules-2 -name "gson-2.14.0.jar" | head -1)
CP=compiler/build/classes/java/main:compiler/build/classes/java/test:core/build/classes/java/main:$GSON:compiler/build/resources/main:core/build/resources/main
for j in $(find /root/.gradle/caches/modules-2 \( -name "junit-jupiter-api*.jar" -o -name "opentest4j*.jar" -o -name "junit-platform-commons*.jar" \) | head -10); do CP=$CP:$j; done
mkdir -p /tmp/claude-0/k8/dbg/out
javac -d /tmp/claude-0/k8/dbg/out -cp "$CP" /tmp/claude-0/k8/dbg/src/com/customdimensions/compiler/datapack/K7Drafts.java 2>&1 | grep -v JAVA_TOOL || true
java -Dcustomdimensions.vanilla="$PWD/compiler/build/vanilla/26.3" -cp "/tmp/claude-0/k8/dbg/out:$CP" com.customdimensions.compiler.datapack.K7Drafts "$@" 2>&1 | grep -v JAVA_TOOL
