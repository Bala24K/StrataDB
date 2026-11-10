#!/usr/bin/env bash
# Runs StrataDB benchmarks. See docs/BENCHMARKS.md.
#
#   scripts/bench.sh workload [--scale small|medium|large] [--runs N] [--label NAME] [--only prefix,...]
#   scripts/bench.sh jmh [JMH options, e.g. CodecBenchmark -prof gc]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

mvn -q -Pbench test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/bench.classpath -Dmdep.includeScope=test
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
CP="target/classes${SEP}target/test-classes${SEP}$(cat target/bench.classpath)"
JVM_OPTS=${JVM_OPTS:-"-Xms1g -Xmx1g"}

mode=${1:-workload}; shift || true
case "$mode" in
  workload) exec java $JVM_OPTS -cp "$CP" com.strata.bench.WorkloadBenchmark "$@" ;;
  jmh)      exec java -cp "$CP" org.openjdk.jmh.Main "$@" ;;
  *) echo "usage: $0 workload|jmh [args]" >&2; exit 2 ;;
esac
