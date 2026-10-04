# PowerShell script to compile and run StrataDB tests on Windows
$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectDir

Write-Host "Compiling StrataDB sources and tests..." -ForegroundColor Cyan
java scripts/Build.java .

Write-Host "`n=== Running StrataDB Test Suite ===" -ForegroundColor Cyan
java -cp "out/classes;out/test-classes" com.strata.testutil.TestRunner
