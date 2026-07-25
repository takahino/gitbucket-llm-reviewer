# start-*.ps1 共通の初期化処理(jarパス解決・config.yml存在チェック)。
# 各スクリプトから ". "$PSScriptRoot\_common.ps1"" でdot-sourceして使う。

function Get-ProjectRoot {
    Split-Path -Parent $PSScriptRoot
}

function Get-ReviewerJarPath {
    $jarPath = Join-Path (Get-ProjectRoot) "target\gitbucket-llm-reviewer.jar"
    if (-not (Test-Path $jarPath)) {
        Write-Error "jarが見つかりません: $jarPath`n先に 'mvn package' を実行してビルドしてください。"
        exit 1
    }
    return $jarPath
}

function Assert-ConfigExists {
    param([Parameter(Mandatory)][string]$ConfigPath)
    if (-not (Test-Path $ConfigPath)) {
        Write-Error "config.ymlが見つかりません: $ConfigPath`nconfig.example.yml (または config.example_high.yml) を config.yml としてコピーし、値を編集してください。"
        exit 1
    }
}
