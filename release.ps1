# Випуск нової версії Спіді на GitHub Releases.
#   .\release.ps1 -Notes "що нового"
# Потрібен gh: `gh auth login` або змінна GH_TOKEN.
# Перед запуском збільш versionCode і versionName в app/build.gradle.kts і закоміть зміни.
param(
    [Parameter(Mandatory = $true)][string]$Notes
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$gradle = Get-Content app\build.gradle.kts -Raw
$versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$tag = "v$versionName"

if (git status --porcelain) { throw "Є незакомічені зміни — спершу закоміть їх." }
if (git tag --list $tag) { throw "Тег $tag уже існує — збільш versionName і versionCode." }

.\gradlew.bat updateManifest "-Pnotes=$Notes" --console=plain
if ($LASTEXITCODE -ne 0) { throw "Збірка не вдалася" }

$out = 'app\build\outputs\update'
git -c "credential.helper=" -c "credential.helper=!gh auth git-credential" push origin HEAD
if ($LASTEXITCODE -ne 0) { throw "git push не вдався" }
gh release create $tag "$out\update.json" "$out\speedy-$versionName.apk" --title "Спіді $versionName" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "Не вдалося створити реліз" }
Write-Host "Реліз $tag опубліковано. Телефон знайде його при запуску або на «Спіді, перевір оновлення»."
