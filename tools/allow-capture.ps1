<#
.SYNOPSIS
  Pre-approves audio/screen capture for Move Bridge so Android stops showing
  the "share your screen?" prompt every time the Move is plugged in.

.DESCRIPTION
  Sets the PROJECT_MEDIA app-op to "allow" for com.renelabs.movebridge.
  Android's capture-consent screen checks this app-op and, when it is allowed,
  returns permission to the app straight away without showing the dialog.

  Run once with the phone connected over USB or wireless debugging.
  Re-run after uninstalling/reinstalling the app or a factory reset.
  Undo with:  -Revoke

.EXAMPLE
  .\allow-capture.ps1
  .\allow-capture.ps1 -Serial 192.168.1.50:5555
  .\allow-capture.ps1 -Revoke
#>
param(
    [switch]$Revoke,
    [string]$Serial,
    [string]$Adb
)

$pkg  = 'com.renelabs.movebridge'
$mode = if ($Revoke) { 'default' } else { 'allow' }

# Find adb: -Adb, then the SDK in ANDROID_HOME / ANDROID_SDK_ROOT / the default location, then PATH.
if (-not $Adb) {
    $candidates = @()
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($root) { $candidates += (Join-Path $root 'platform-tools\adb.exe') }
    }
    $candidates += "$env:LOCALAPPDATA\Android\platform-tools\adb.exe"
    $Adb = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $Adb) { $cmd = Get-Command adb -ErrorAction SilentlyContinue; if ($cmd) { $Adb = $cmd.Source } }
}
if (-not $Adb -or -not (Test-Path $Adb)) { Write-Error 'adb not found. Pass -Adb <path to adb.exe>.'; exit 1 }

# Pick the phone. With several devices attached, -Serial is required.
$devices = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] })
if (-not $Serial) {
    if ($devices.Count -eq 0) { Write-Error 'No authorized phone found. Connect it and accept the USB debugging prompt.'; exit 1 }
    if ($devices.Count -gt 1) { Write-Error ("More than one device attached. Pick one with -Serial: " + ($devices -join ', ')); exit 1 }
    $Serial = $devices[0]
} elseif ($devices -notcontains $Serial) {
    Write-Error "Device $Serial is not connected and authorized."; exit 1
}

& $Adb -s $Serial shell appops set $pkg PROJECT_MEDIA $mode
$result = & $Adb -s $Serial shell appops get $pkg PROJECT_MEDIA
Write-Output "Move Bridge capture approval on ${Serial}: $result"
