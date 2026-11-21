#!/usr/bin/env bash
W=/tmp/claude-0/w10c1p; cd /home/user/minecraft-server-template/mods/custom-dimensions-26
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
timeout 1800 ./gradlew --offline --max-workers=3 :compiler:test -PupdateGolden=true > $W/runs/golden.log 2>&1; echo "golden rc=$?" > $W/runs/build.rc
timeout 3600 ./gradlew --offline --max-workers=3 build > $W/runs/build.log 2>&1; echo "build rc=$?" >> $W/runs/build.rc
