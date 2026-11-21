#!/usr/bin/env bash
# st-terrain.sh: K10's boot 1 again, with CI's server.properties (max-tick-time=-1): the terrain self-test over the lane's
# scratch users plus every self-test fixture -> out/selftest.txt, out/selftest-server.log
set -u
P=/home/user/minecraft-server-template/mods/custom-dimensions-26; OUT=/tmp/claude-0/w10c2/k10lane/out
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
cd $P; rm -rf server/run/world
printf 'online-mode=false\nmax-tick-time=-1\nview-distance=4\nsimulation-distance=4\nlevel-seed=20261022\n' > server/run/server.properties
rm -f $OUT/selftest.txt; start=$(date +%s)
timeout 5400 ./gradlew --offline --max-workers=3 :server:runServer -Pdefinitions=$OUT/defs-selftest -PselfTest=$OUT/selftest.txt -PselfTestOnly=terrain < /dev/null > $OUT/selftest-server.log 2>&1
echo "self-test (CI props) rc=$? $(( $(date +%s) - start )) s $(grep -c '^PASS' $OUT/selftest.txt 2>/dev/null) PASS $(grep -c '^FAIL' $OUT/selftest.txt 2>/dev/null) FAIL" >> $OUT/driver.log
rm -rf server/run/world; touch $OUT/st.done
