# Runs StrataDB benchmarks. See docs/BENCHMARKS.md.
#
#   powershell -File scripts/bench.ps1 workload --scale small --runs 3 --label baseline
#   powershell -File scripts/bench.ps1 jmh CodecBenchmark -prof gc
param([string]$Mode = "workload", [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
mvn -q -Pbench test-compile dependency:build-classpath "-Dmdep.outputFile=target/bench.classpath" "-Dmdep.includeScope=test"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$cp = "target/classes;target/test-classes;" + (Get-Content target/bench.classpath -Raw).Trim()
if ($Mode -eq "workload") {
    java -Xms1g -Xmx1g -cp $cp com.strata.bench.WorkloadBenchmark @Rest
} elseif ($Mode -eq "jmh") {
    java -cp $cp org.openjdk.jmh.Main @Rest
} else {
    Write-Error "usage: bench.ps1 workload|jmh [args]"
}
exit $LASTEXITCODE
