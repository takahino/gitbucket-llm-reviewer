# start-*.ps1 共通の初期化処理・実行処理(jarパス解決・config.yml存在チェック・作業ディレクトリ切替)。
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

# ConfigPathが未指定(空)ならプロジェクトルート直下の config.yml を既定値として返す。
function Resolve-ConfigPath {
    param([string]$ConfigPath)
    if ($ConfigPath) {
        return $ConfigPath
    }
    return Join-Path (Get-ProjectRoot) "config.yml"
}

# config.ymlのworkDir/state.filePath等の相対パスがプロジェクトルート基準で解決されるよう、
# カレントディレクトリをプロジェクトルートに切り替えてからjarを実行する(スクリプトの呼び出し元がどこでも動くように)。
function Invoke-ReviewerJar {
    param(
        [Parameter(Mandatory)][string[]]$JarArgs,
        [string]$StartMessage
    )
    Push-Location (Get-ProjectRoot)
    try {
        if ($StartMessage) { Write-Host $StartMessage }
        & java @JarArgs
    } finally {
        Pop-Location
    }
}
