<#
.SYNOPSIS
    設定リポジトリのPRを polling.intervalSeconds 毎に走査し続ける常駐モードで起動する。

.PARAMETER ConfigPath
    config.ymlのパス。既定はプロジェクトルート直下の config.yml。

.PARAMETER DryRun
    生成したレビューコメントをGitBucketへ投稿せず、ログ出力のみで確認する。

.EXAMPLE
    .\scripts\start-polling.ps1
    .\scripts\start-polling.ps1 -DryRun
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

$jarArgs = @("-jar", $jarPath, "--config", $ConfigPath)
if ($DryRun) { $jarArgs += "--dry-run" }

Push-Location $projectRoot
try {
    Write-Host "ポーリングモードで起動します(config=$ConfigPath, dryRun=$($DryRun.IsPresent))。停止するにはCtrl+C。"
    & java @jarArgs
} finally {
    Pop-Location
}
