<#
.SYNOPSIS
    config.yml編集・review.yml表示用の管理UI(--ui)を起動する。

.PARAMETER ConfigPath
    config.ymlのパス。既定はプロジェクトルート直下の config.yml。

.PARAMETER Port
    管理UIの待受ポート。既定は 8765(127.0.0.1のみで待受、外部公開不可)。

.EXAMPLE
    .\scripts\start-ui.ps1
    .\scripts\start-ui.ps1 -Port 9000
#>
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [int]$Port = 8765
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_common.ps1"

$projectRoot = Get-ProjectRoot
if (-not $ConfigPath) {
    $ConfigPath = Join-Path $projectRoot "config.yml"
}
$jarPath = Get-ReviewerJarPath
Assert-ConfigExists -ConfigPath $ConfigPath

Push-Location $projectRoot
try {
    Write-Host "管理UIを起動します: http://127.0.0.1:$Port/ (Ctrl+Cで終了)"
    & java -jar $jarPath --config $ConfigPath --ui --ui-port $Port
} finally {
    Pop-Location
}
