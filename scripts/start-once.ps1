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

$ConfigPath = Resolve-ConfigPath -ConfigPath $ConfigPath
$jarPath = Get-ReviewerJarPath
Assert-ConfigExists -ConfigPath $ConfigPath

$jarArgs = @("-jar", $jarPath, "--config", $ConfigPath, "--once")
if ($DryRun) { $jarArgs += "--dry-run" }

Invoke-ReviewerJar `
    -JarArgs $jarArgs `
    -StartMessage "1回だけ走査します(config=$ConfigPath, dryRun=$($DryRun.IsPresent))"
