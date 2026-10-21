param(
    [string]$Tv = "192.168.8.133:5555",
    [string]$Adb = "$env:USERPROFILE\Downloads\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$project = Split-Path -Parent $PSScriptRoot
$package = "ru.radiationx.anilibria.app.tv"
Push-Location $project
try {
    if (-not (Test-Path -LiteralPath $Adb)) { throw "Не найден adb: $Adb" }
    git pull --ff-only
    if ($LASTEXITCODE -ne 0) { throw "Ошибка обновления — сборка отменена" }

    & .\gradlew.bat :app-tv:assembleAppDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Ошибка сборки — установка отменена" }

    $apk = Join-Path $project "app-tv\build\outputs\apk\app\debug\app-tv-app-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Сборка не создала APK" }

    & $Adb connect $Tv
    if ($LASTEXITCODE -ne 0) { throw "Не удалось подключиться к ТВ" }
    $state = & $Adb -s $Tv get-state
    if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne "device") {
        throw "ТВ не готов: проверь подключение и разрешение отладки"
    }
    & $Adb -s $Tv install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Ошибка установки — запуск отменён" }

    & $Adb -s $Tv shell am force-stop $package
    if ($LASTEXITCODE -ne 0) { throw "Не удалось остановить предыдущий экземпляр" }
    & $Adb -s $Tv shell am start -W -n "$package/ru.radiationx.anilibria.screen.launcher.MainActivity"
    if ($LASTEXITCODE -ne 0) { throw "Не удалось запустить приложение" }
} finally {
    Pop-Location
}
