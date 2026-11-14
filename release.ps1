# Випуск нової версії Спіді на GitHub Releases.
#   .\release.ps1 -Notes "що нового"
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
git push origin HEAD
gh release create $tag "$out\update.json" "$out\speedy-$versionName.apk" --title "Спіді $versionName" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "Не вдалося створити реліз" }
Write-Host "Реліз $tag опубліковано. Телефон знайде його при запуску або на «Спіді, перевір оновлення»."
