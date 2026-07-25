<#
.SYNOPSIS
    設定リポジトリのopen PRを1回だけ走査してレビューし、終了する(--once)。

.PARAMETER ConfigPath
    config.ymlのパス。既定はプロジェクトルート直下の config.yml。

.PARAMETER DryRun
    生成したレビューコメントをGitBucketへ投稿せず、ログ出力のみで確認する。

.EXAMPLE
    .\scripts\start-once.ps1
    .\scripts\start-once.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_common.ps1"

$projectRoot = Get-ProjectRoot
if (-not $ConfigPath) {
    $ConfigPath = Join-Path $projectRoot "config.yml"
}
$jarPath = Get-ReviewerJarPath
Assert-ConfigExists -ConfigPath $ConfigPath

$jarArgs = @("-jar", $jarPath, "--config", $ConfigPath, "--once")
if ($DryRun) { $jarArgs += "--dry-run" }

Push-Location $projectRoot
try {
    Write-Host "1回だけ走査します(config=$ConfigPath, dryRun=$($DryRun.IsPresent))"
    & java @jarArgs
} finally {
    Pop-Location
}
