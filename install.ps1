$ErrorActionPreference = 'Stop'

function Write-Step {
    param([string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Get-CommandPath {
    param([string[]]$Names)
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }
    return $null
}

function Get-ConfiguredPort {
    param(
        [string]$Name,
        [int]$Default
    )

    $configPath = Join-Path $PSScriptRoot 'configuration.yaml'
    if (-not (Test-Path $configPath)) {
        return $Default
    }

    $match = Select-String -Path $configPath -Pattern "^\s*$Name:\s*(\d+)" | Select-Object -First 1
    if ($match -and $match.Matches.Count -gt 0) {
        return [int]$match.Matches[0].Groups[1].Value
    }

    return $Default
}

function Test-AdbDevice {
    param([string]$AdbPath)

    $lines = & $AdbPath devices
    return @($lines | Where-Object { $_ -match "\tdevice$" }).Count -gt 0
}

$pythonPath = $null
$pythonArgs = @()
$ffmpegPath = $null
$adbPath = $null
$videoPort = Get-ConfiguredPort -Name 'video_port' -Default 5000
$controlPort = Get-ConfiguredPort -Name 'control_port' -Default 5002
$apkPath = Join-Path $PSScriptRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$shortcutPath = Join-Path ([Environment]::GetFolderPath('Desktop')) 'PocketDisplay USB.lnk'

Write-Step 'Checking Python 3.10+'
$pythonPath = Get-CommandPath -Names @('py', 'python')
if (-not $pythonPath) {
    throw 'Python 3.10+ is required. Download: https://www.python.org/downloads/windows/'
}
if ([IO.Path]::GetFileName($pythonPath).ToLowerInvariant() -eq 'py.exe') {
    $pythonArgs = @('-3')
}
$pythonVersionText = (& $pythonPath @pythonArgs -c "import sys; print('.'.join(map(str, sys.version_info[:3])))").Trim()
$pythonVersion = [version]$pythonVersionText
if ($pythonVersion.Major -lt 3 -or ($pythonVersion.Major -eq 3 -and $pythonVersion.Minor -lt 10)) {
    throw "Found Python $pythonVersionText. Python 3.10+ is required."
}
Write-Host "Using Python $pythonVersionText at $pythonPath" -ForegroundColor Green

Write-Step 'Checking FFmpeg'
$ffmpegPath = Get-CommandPath -Names @('ffmpeg')
if (-not $ffmpegPath) {
    Write-Warning 'FFmpeg was not found on PATH.'
    Write-Warning 'Download FFmpeg from https://ffmpeg.org/download.html and add it to PATH.'
} else {
    Write-Host "Using FFmpeg at $ffmpegPath" -ForegroundColor Green
}

Write-Step 'Checking ADB'
$adbPath = Get-CommandPath -Names @('adb')
if (-not $adbPath -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
    $adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
}
if (-not $adbPath) {
    throw 'ADB was not found. Download Platform-Tools: https://developer.android.com/tools/releases/platform-tools'
}
Write-Host "Using ADB at $adbPath" -ForegroundColor Green

Write-Step 'Installing Python dependencies'
& $pythonPath @pythonArgs -m pip install -r (Join-Path $PSScriptRoot 'server\requirements.txt')

Write-Step 'Building Android APK if possible'
$gradlePath = Join-Path $PSScriptRoot 'android\gradlew.bat'
if ($env:JAVA_HOME -and (Test-Path $gradlePath)) {
    Push-Location (Join-Path $PSScriptRoot 'android')
    try {
        & .\gradlew.bat assembleDebug
    } finally {
        Pop-Location
    }
} else {
    Write-Warning 'Skipping APK build because JAVA_HOME or android\gradlew.bat is unavailable.'
}

Write-Step 'Installing APK to device if available'
if ((Test-Path $apkPath) -and (Test-AdbDevice -AdbPath $adbPath)) {
    & $adbPath install -r $apkPath
} elseif (-not (Test-Path $apkPath)) {
    Write-Warning "APK not found at $apkPath. Build the Android app first if you want automatic install."
} else {
    Write-Warning 'No connected ADB device found. Skipping APK installation.'
}

Write-Step 'Setting up ADB reverse ports'
if (Test-AdbDevice -AdbPath $adbPath) {
    & $adbPath reverse "tcp:$videoPort" "tcp:$videoPort"
    $controlResult = & $adbPath reverse "tcp:$controlPort" "tcp:$controlPort" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Control port reverse failed for $controlPort. Continuing because USB mode uses the video socket for touch input."
        if ($controlResult) {
            Write-Warning ($controlResult | Out-String).Trim()
        }
    }
} else {
    Write-Warning 'No connected ADB device found. Skipping reverse port setup.'
}

Write-Step 'Adding Windows Firewall rule for video port'
if (Get-Command Get-NetFirewallRule -ErrorAction SilentlyContinue) {
    $ruleName = "PocketDisplay Video $videoPort"
    $existingRule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if (-not $existingRule) {
        try {
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort $videoPort | Out-Null
            Write-Host "Created firewall rule: $ruleName" -ForegroundColor Green
        } catch {
            Write-Warning 'Could not create the firewall rule. Re-run PowerShell as Administrator if needed.'
        }
    } else {
        Write-Host "Firewall rule already exists: $ruleName" -ForegroundColor Green
    }
} else {
    Write-Warning 'NetSecurity cmdlets are unavailable; firewall rule was not created.'
}

Write-Step 'Creating desktop shortcut'
$wshShell = New-Object -ComObject WScript.Shell
$shortcut = $wshShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = Join-Path $PSScriptRoot 'start_usb.bat'
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,220"
$shortcut.Save()
Write-Host "Created shortcut: $shortcutPath" -ForegroundColor Green

Write-Step 'Done'
Write-Host 'Installation completed.' -ForegroundColor Green
Write-Host 'Start the project with .\start_usb.bat and use 127.0.0.1 on the phone for USB mode.' -ForegroundColor Green
