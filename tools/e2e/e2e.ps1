# End-to-end test harness for TarangFX (adb + uiautomator + ffprobe).
$global:adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# Scratch folder for pulled outputs/screenshots (override by setting $env:TARANGFX_E2E_DIR).
$global:S = if ($env:TARANGFX_E2E_DIR) { $env:TARANGFX_E2E_DIR } else { Join-Path $env:TEMP "tarangfx-e2e" }
New-Item -ItemType Directory -Force $global:S | Out-Null
$global:ffprobe = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffprobe.exe | Select-Object -First 1).FullName
$global:PKG = "com.pnprojects.tarangfx"
$global:OUT = "/sdcard/Music/TarangFX"
if (-not $global:Results) { $global:Results = New-Object System.Collections.ArrayList }

function Check([string]$name, [bool]$ok, [string]$detail = "") {
    $line = "{0} | {1} | {2}" -f ($(if ($ok) { "PASS" } else { "FAIL" })), $name, $detail
    [void]$global:Results.Add($line)
    Write-Output $line
}

function Get-UiXml {
    for ($i = 0; $i -lt 6; $i++) {
        $r = & $adb shell uiautomator dump /sdcard/ui.xml 2>&1
        if ("$r" -match "dumped") { return ((& $adb shell cat /sdcard/ui.xml) -join "") }
        Start-Sleep -Milliseconds 800
    }
    return ""
}

function Esc([string]$s) { [regex]::Escape(($s -replace '&', '&amp;' -replace '"', '&quot;')) }

# Returns @{x;y;w;h} for the n-th (0-based, -1 = last) node whose text/content-desc equals label.
function Find-Node([string]$label, [int]$index = 0, [string]$xml = $null, [switch]$Contains) {
    if (-not $xml) { $xml = Get-UiXml }
    $pat = if ($Contains) { "(?:text|content-desc)=""[^""]*$(Esc $label)[^""]*""" } else { "(?:text|content-desc)=""$(Esc $label)""" }
    $ms = [regex]::Matches($xml, "$pat[^>]*?bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    if ($ms.Count -eq 0) { return $null }
    $m = if ($index -lt 0) { $ms[$ms.Count + $index] } else { $ms[[Math]::Min($index, $ms.Count - 1)] }
    $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value; $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
    return @{ x = [int](($x1 + $x2) / 2); y = [int](($y1 + $y2) / 2); w = $x2 - $x1; h = $y2 - $y1 }
}

function Has([string]$label, [switch]$Contains) { return $null -ne (Find-Node $label -Contains:$Contains) }

function Tap([string]$label, [double]$wait = 1.5, [int]$index = 0, [switch]$Contains) {
    $n = Find-Node $label $index -Contains:$Contains
    if (-not $n -or $n.w -lt 4) { Write-Output "  (not found: $label)"; return $false }
    & $adb shell input tap $n.x $n.y
    Start-Sleep -Milliseconds ([int]($wait * 1000))
    return $true
}

function TapXY([int]$x, [int]$y, [double]$wait = 1) { & $adb shell input tap $x $y; Start-Sleep -Milliseconds ([int]($wait * 1000)) }

function Wait-For([string]$label, [int]$timeoutSec = 20, [switch]$Contains) {
    $t = 0
    while ($t -lt $timeoutSec) { if (Has $label -Contains:$Contains) { return $true }; Start-Sleep 1; $t++ }
    return $false
}

# Taps a chip in a horizontally scrolling row, swiping the row left until it is visible.
function Tap-Chip([string]$label, [string]$anchor, [double]$wait = 1) {
    for ($i = 0; $i -lt 4; $i++) {
        $xml = Get-UiXml
        $n = Find-Node $label 0 $xml
        if ($n -and $n.w -gt 40 -and $n.x -gt 30 -and $n.x -lt 1050) { & $adb shell input tap $n.x $n.y; Start-Sleep -Milliseconds ([int]($wait * 1000)); return $true }
        $a = Find-Node $anchor 0 $xml
        if (-not $a) { $a = $n }
        if (-not $a) { break }
        & $adb shell input swipe 900 $a.y 250 $a.y 300
        Start-Sleep -Milliseconds 600
    }
    Write-Output "  (chip not found: $label)"; return $false
}

function Back([double]$wait = 1.2) { & $adb shell input keyevent KEYCODE_BACK; Start-Sleep -Milliseconds ([int]($wait * 1000)) }

function Restart-App {
    & $adb shell am force-stop $PKG
    & $adb shell am start -n "$PKG/.MainActivity" | Out-Null
    [void](Wait-For "Trim audio" 25)
    Wait-IntroGone
    Start-Sleep 1
}

# The launch intro overlays Home (and swallows taps to skip itself): wait until it has faded.
function Wait-IntroGone([int]$timeoutSec = 10) {
    for ($i = 0; $i -lt $timeoutSec * 2; $i++) {
        if (-not (Has "Made with love" -Contains)) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# Replace the text of the focused field.
function Type-Text([string]$text) {
    & $adb shell input keyevent KEYCODE_MOVE_END
    & $adb shell ("input keyevent " + ((1..80 | ForEach-Object { "67" }) -join " "))   # 80x DEL
    # One character per injection: bulk 'input text' races Compose's IME handling and scrambles order.
    foreach ($ch in $text.ToCharArray()) {
        $c = if ($ch -eq ' ') { '%s' } else { "$ch" }
        & $adb shell input text "'$c'"
        Start-Sleep -Milliseconds 60
    }
    Start-Sleep -Milliseconds 500
}

function Hide-Keyboard { & $adb shell input keyevent KEYCODE_ESCAPE; Start-Sleep -Milliseconds 400 }

function Out-Files { (& $adb shell ls $OUT 2>$null) }

function Newest-Output([string]$like = "*") {
    $f = (& $adb shell "ls -t '$OUT'" 2>$null) | Where-Object { $_ -like $like } | Select-Object -First 1
    return $f
}

# Pulls a device file and returns an ffprobe summary object.
function Probe([string]$devicePath) {
    $local = Join-Path $S ("probe_" + [IO.Path]::GetFileName($devicePath))
    & $adb pull "$devicePath" "$local" 2>&1 | Out-Null
    if (-not (Test-Path $local)) { return $null }
    $j = & $ffprobe -v error -show_format -show_streams -of json "$local" | ConvertFrom-Json
    $a = $j.streams | Where-Object { $_.codec_type -eq "audio" } | Select-Object -First 1
    $v = $j.streams | Where-Object { $_.codec_type -eq "video" } | Select-Object -First 1
    $tags = @{}
    foreach ($src in @($j.format.tags, $a.tags)) { if ($src) { $src.PSObject.Properties | ForEach-Object { $tags[$_.Name.ToLower()] = $_.Value } } }
    return [pscustomobject]@{
        File = [IO.Path]::GetFileName($devicePath); Format = $j.format.format_name
        Codec = $a.codec_name; Rate = [int]$a.sample_rate; Channels = [int]$a.channels
        Duration = [double]$j.format.duration; BitRate = [int]$j.format.bit_rate
        SampleFmt = $a.sample_fmt; BitsRaw = $a.bits_per_raw_sample; Cover = [bool]$v; Tags = $tags; Local = $local
    }
}

function Crashes {
    $c = & $adb logcat -d -s AndroidRuntime:E | Select-String "FATAL|Caused by" | Select-Object -First 4
    return ($c -join " / ")
}

function Shot([string]$name) {
    & $adb shell screencap -p /sdcard/shot.png
    & $adb pull /sdcard/shot.png "$S\$name.png" 2>&1 | Out-Null
}

# Opens a tool from Home and picks files (by visible title) using the picker's search box.
function Pick-Files([string[]]$files) {
    $searchOpen = $false
    foreach ($spec in $files) {
        # "query=>title": search by query (e.g. file name), then tap the row with this title.
        $query, $f = if ($spec -match '=>') { $spec -split '=>' } else { $spec, $spec }
        if ($query -ne $f -or -not (Has $f)) {
            if (-not $searchOpen) { [void](Tap "Search" 1); $searchOpen = $true }
            Type-Text $query; Start-Sleep 1
        }
        $n = $null
        $xml = Get-UiXml
        $ms = [regex]::Matches($xml, "text=""$(Esc $f)""[^>]*?bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        foreach ($m in $ms) { if ([int]$m.Groups[2].Value -gt 300) { $n = $m; break } }   # skip the search field itself
        if (-not $n) { Write-Output "  (file not found: $f)"; return $false }
        & $adb shell input tap ((([int]$n.Groups[1].Value) + ([int]$n.Groups[3].Value)) / 2) ((([int]$n.Groups[2].Value) + ([int]$n.Groups[4].Value)) / 2)
        Start-Sleep 1.2
    }
    if (Has "Continue" -Contains) { Hide-Keyboard; [void](Tap "Continue" 3 -Contains) }
    Start-Sleep 2
    return $true
}

function Open-Tool([string]$tool, [string[]]$files) {
    Restart-App
    if (-not (Tap $tool 2.5)) { return $false }
    return (Pick-Files $files)
}

# Export sheet: optional name + format chip, then the sheet's Save (last "Save"/button text).
function Export-Sheet([string]$name = $null, [string]$codec = $null, [string]$confirm = "Save", [string[]]$extraChips = @()) {
    if ($name) {
        $n = Find-Node $name -Contains
        if (-not $n) { $n = Find-Node "File name" }
        if ($n) { TapXY $n.x $n.y 0.6; Type-Text $name; Hide-Keyboard }
    }
    if ($codec) { [void](Tap-Chip $codec "MP3") }
    foreach ($c in $extraChips) { [void](Tap-Chip $c "Original") }
    $btn = Find-Node $confirm -1
    if ($btn) { TapXY $btn.x $btn.y 1 }
}

function Wait-Export([int]$timeoutSec = 90) {
    $t = 0
    while ($t -lt $timeoutSec) {
        $xml = Get-UiXml
        if ($xml -match 'text="Saved"' -or $xml -match 'text="Finished with errors"' -or $xml -match 'text="Cancelled"') {
            $failed = $xml -match 'text="Failed"'
            $err = [regex]::Match($xml, 'text="Failed"').Success
            [void](Tap "Close" 1)
            return (-not $failed)
        }
        Start-Sleep 2; $t += 2
    }
    return $false
}
