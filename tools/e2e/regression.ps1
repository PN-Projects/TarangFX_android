# Regression suite: installs an APK on the connected device/emulator and drives every major
# feature through the real UI, verifying each output file with ffprobe/ffmpeg/Python analysers.
#
#   . .\tools\e2e\regression.ps1 -Apk dist\TarangFX-1.0.0-x86_64-release.apk -Media <folder>
#
# <folder> must contain Test_Chords.wav (20 s), Noisy_Speech.wav, Music_Tagged.mp3 (tags + cover),
# Video_Clip.mp4 (12 s) and Long_60min.mp3 (see README.md for how they are generated).
param(
    [Parameter(Mandatory)] [string]$Apk,
    [Parameter(Mandatory)] [string]$Media
)
. (Join-Path $PSScriptRoot "e2e.ps1")
$global:Results = New-Object System.Collections.ArrayList
$ff = $ffprobe -replace 'ffprobe.exe', 'ffmpeg.exe'
$py = Join-Path $PSScriptRoot "snr.py"

function Lufs($path) {
    $o = & $ff -hide_banner -nostats -i $path -af ebur128=peak=true -f null NUL 2>&1 | Out-String
    $s = $o.Substring($o.LastIndexOf("Summary"))
    return @([double]([regex]::Match($s, 'I:\s+(-?[\d.]+) LUFS').Groups[1].Value), [double]([regex]::Match($s, 'Peak:\s+(-?[\d.]+) dBFS').Groups[1].Value))
}
# Runs UI steps, then verifies the file that step CREATED (diff of the output folder), so a step
# that silently does nothing can't pass by re-reading an older output with a similar name.
function New-Output([string[]]$before, [string]$like) {
    (& $adb shell "ls -t '$OUT'" 2>$null) | Where-Object { $_ -like $like -and $before -notcontains $_ } | Select-Object -First 1
}
function Smoke([string]$name, [scriptblock]$steps, [string]$like, [scriptblock]$verify) {
    $before = @(& $adb shell "ls '$OUT'" 2>$null)
    & $steps | Out-Null
    $f = New-Output $before $like
    $p = if ($f) { Probe "$OUT/$f" } else { $null }
    $ok = $p -and (& $verify $p)
    Write-Host (Check $name $ok "file=$f codec=$($p.Codec) ch=$($p.Channels) dur=$([math]::Round($p.Duration, 3))s")
    return $p
}

# ------------------------------------------------------------------ install (clean)
& $adb uninstall $PKG 2>&1 | Out-Null
& $adb shell "rm -rf $OUT" | Out-Null
$r = & $adb install $Apk 2>&1
Check "Install $([IO.Path]::GetFileName($Apk))" ("$r" -match "Success") "$r"
foreach ($p in "READ_MEDIA_AUDIO", "READ_MEDIA_VIDEO", "POST_NOTIFICATIONS", "RECORD_AUDIO") { & $adb shell pm grant $PKG "android.permission.$p" }
& $adb shell appops set $PKG WRITE_SETTINGS allow
& $adb logcat -c

$dests = @{ "Test_Chords.wav" = "Music"; "Noisy_Speech.wav" = "Music"; "Music_Tagged.mp3" = "Music"; "Long_60min.mp3" = "Music"; "Video_Clip.mp4" = "Movies" }
foreach ($k in $dests.Keys) {
    & $adb push (Join-Path $Media $k) "/sdcard/$($dests[$k])/$k" | Out-Null
    & $adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/$($dests[$k])/$k" | Out-Null
}
# Wait until MediaStore has indexed every test file (the picker lists MediaStore rows).
$indexed = $false
for ($i = 0; $i -lt 30 -and -not $indexed; $i++) {
    $rows = (& $adb shell content query --uri content://media/external/file --projection _display_name) -join "`n"
    $indexed = ($dests.Keys | Where-Object { $rows -notmatch [regex]::Escape($_) }).Count -eq 0
    if (-not $indexed) { Start-Sleep 1 }
}
Check "Test media indexed by MediaStore" $indexed "$($dests.Count) files"

# ------------------------------------------------------------------ static / system checks
$dump = (& $adb shell dumpsys package $PKG) -join "`n"
Check "No network permission granted (installed package)" (-not ($dump -match "android.permission.INTERNET|ACCESS_NETWORK_STATE")) ""
Check "Release build is not debuggable" (-not ($dump -match "flags=\[[^\]]*DEBUGGABLE")) ""
# Median of 3 cold starts: a single sample is skewed by host load or media indexing right after install.
$times = foreach ($i in 1..3) {
    & $adb shell am force-stop $PKG; Start-Sleep 2
    $st = (& $adb shell am start -W -n "$PKG/.MainActivity") -join " "
    [int]([regex]::Match($st, "TotalTime: (\d+)").Groups[1].Value)
}
$total = ($times | Sort-Object)[1]
Check "Cold start (median of 3)" ($total -gt 0 -and $total -lt 2000) "TotalTime=${total} ms (runs: $($times -join ', ') ms)"
Start-Sleep 2

# ------------------------------------------------------------------ feature smoke tests
[void](Smoke "Trim 20 s -> 19 s, FLAC" { [void](Open-Tool "Trim audio" @("Test_Chords")); $m = Find-Node "Minus 0.1 seconds" 1; 1..10 | ForEach-Object { TapXY $m.x $m.y 0.2 }; [void](Tap "Confirm" 2); Export-Sheet -name "reg_trim" -codec "FLAC"; [void](Wait-Export 60) } "reg_trim*" { param($p) $p.Codec -eq "flac" -and [math]::Abs($p.Duration - 19) -lt 0.05 })
[void](Smoke "Merge 2 files (20 + 15.69 s) -> MP3" { [void](Open-Tool "Merge" @("Test_Chords", "Noisy_Speech")); [void](Tap "Save" 2); Export-Sheet -name "reg_merge"; [void](Wait-Export 90) } "reg_merge*" { param($p) $p.Codec -eq "mp3" -and [math]::Abs($p.Duration - 35.69) -lt 0.1 })
[void](Smoke "Multitrack mix 2 tracks -> M4A" { [void](Open-Tool "Mix" @("Test_Chords", "Noisy_Speech")); if (Has "Start new") { [void](Tap "Start new" 1) }; [void](Tap "Save" 2); Export-Sheet -name "reg_mix" -codec "M4A"; [void](Wait-Export 90) } "reg_mix*" { param($p) $p.Codec -eq "aac" -and [math]::Abs($p.Duration - 20) -lt 0.1 })
[void](Smoke "Convert tagged MP3 -> FLAC keeps tags + cover" { [void](Open-Tool "Convert format" @("Music_Tagged=>Tagged Song")); [void](Tap-Chip "FLAC" "MP3" 1); [void](Tap "Convert 1 file" 2); [void](Wait-Export 60) } "Tagged Song*.flac" { param($p) $p.Tags["artist"] -eq "Test Artist" -and $p.Cover })
[void](Smoke "Video -> audio stream copy (M4A)" { [void](Open-Tool "Video to audio" @("Video_Clip")); [void](Tap "Extract 1 file" 2); [void](Wait-Export 60) } "Video_Clip*.m4a" { param($p) $p.Codec -eq "aac" -and [math]::Abs($p.Duration - 12) -lt 0.1 })
[void](Smoke "FX 2x speed -> WAV (20 s -> 10 s)" { Restart-App; [void](Tap "FX & EQ" 2.5); [void](Tap "Folders" 1.5); [void](Tap "Music" 1.5); [void](Tap "Test_Chords" 3); [void](Tap "Speed & Pitch" 1); [void](Tap-Chip "2.0x" "1.0x" 1); [void](Tap "Save" 2); Export-Sheet -name "reg_fx" -codec "WAV"; [void](Wait-Export 90) } "reg_fx*" { param($p) [math]::Abs($p.Duration - 10) -lt 0.1 })
[void](Smoke "Record WAV (mic)" { Restart-App; [void](Tap "Record" 2.5); [void](Tap "WAV" 1); [void](Tap "Start recording" 3); [void](Tap "Stop and save" 3); [void](Tap "Got it" 1) } "Recording*.wav" { param($p) $p.Duration -gt 1 })
[void](Smoke "Tag editor saves a tagged copy" { Restart-App; [void](Tap "Tag Editor" 2.5); [void](Tap "Folders" 1.5); [void](Tap "Music" 1.5); [void](Tap "Tagged Song" 3); [void](Tap "Save tagged copy" 2); [void](Wait-Export 60) } "Tagged Song*.mp3" { param($p) $p.Tags["album"] -eq "Test Album" })

# AI denoise: Studio (DeepFilterNet3) and Fast (GTCRN), measured noise floor / speech-to-noise.
$base = (python -I $py (Join-Path $Media "Noisy_Speech.wav")) -join ""
$baseSnr = [double]([regex]::Match($base, "speech/noise\s+(-?[\d.]+)").Groups[1].Value)
foreach ($mode in "Studio", "Fast") {
    $p = Smoke "AI denoise $mode -> WAV, same length" {
        [void](Open-Tool "AI Denoise" @("Noisy_Speech")); Start-Sleep 2
        # If the mode button is missing, export nothing so the check fails instead of silently
        # re-testing the default mode.
        if (-not (Tap "$mode Denoise" 1.5)) { Write-Host "  mode '$mode Denoise' not found"; return }
        [void](Tap "Save" 2.5); [void](Tap-Chip "WAV" "MP3" 1); $b = Find-Node "Save" -1; TapXY $b.x $b.y 2; [void](Wait-Export 120)
    } "Noisy_Speech*.wav" { param($p) [math]::Abs($p.Duration - 15.69) -lt 0.05 }
    if ($p) {
        $o = (python -I $py $p.Local) -join ""
        $snr = [double]([regex]::Match($o, "speech/noise\s+(-?[\d.]+)").Groups[1].Value)
        $floor = [regex]::Match($o, "noise floor\s+(-?[\d.]+)").Groups[1].Value
        Write-Host (Check "AI denoise $mode improves speech/noise by > 20 dB" ($snr - $baseSnr -gt 20) "speech/noise $baseSnr -> $snr dB, noise floor $floor dBFS")
    }
}

# Voice Enhance: integrated loudness must land on -14 LUFS (measured independently by ffmpeg).
# Tolerance +/-0.3 LU (the in-app refinement targets 0.05 LU on its own BS.1770 meter).
foreach ($case in @(@("Mono", "mono"), @("Stereo", "stereo"))) {
    $p = Smoke "Voice Enhance export ($($case[1]))" {
        [void](Open-Tool "AI Denoise" @("Noisy_Speech")); Start-Sleep 2; [void](Tap "Voice Enhance" 2)
        [void](Tap "Save" 2.5); [void](Tap-Chip "WAV" "MP3" 1); [void](Tap-Chip $case[0] "Original" 1); $b = Find-Node "Save" -1; TapXY $b.x $b.y 2; [void](Wait-Export 120)
    } "Noisy_Speech*.wav" { param($p) $p.Channels -eq $(if ($case[1] -eq "mono") { 1 } else { 2 }) }
    if ($p) {
        $l = Lufs $p.Local
        Write-Host (Check "Voice Enhance -14 LUFS ($($case[1]) output, ffmpeg EBU R128)" ([math]::Abs($l[0] + 14) -le 0.3 -and $l[1] -lt 0) "integrated=$($l[0]) LUFS truePeak=$($l[1]) dBFS")
    }
}

# 60-minute file: waveform extraction + editor memory on the release build.
Restart-App
$t0 = Get-Date
[void](Tap "Trim audio" 2.5); [void](Pick-Files @("Long_60min"))
$ready = $false
for ($i = 0; $i -lt 30 -and -not $ready; $i++) { $ready = Has "Confirm"; if (-not $ready) { Start-Sleep 1 } }
Start-Sleep 3
$sec = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
$pss = [int]([regex]::Match(((& $adb shell dumpsys meminfo $PKG) -join " "), "TOTAL PSS:\s+(\d+)").Groups[1].Value) / 1024
Check "60-min MP3 opens in Trim (waveform built)" $ready "ready after ${sec}s incl. UI navigation; PSS $([math]::Round($pss)) MB"
Check "60-min editing memory < 300 MB PSS" ($pss -gt 0 -and $pss -lt 300) "PSS $([math]::Round($pss)) MB"
Back; Back

$c = Crashes
Check "No crashes / ANRs during the run" ([string]::IsNullOrEmpty($c)) "$c"
$pass = ($global:Results | Where-Object { $_ -like "PASS*" }).Count
$fail = ($global:Results | Where-Object { $_ -like "FAIL*" }).Count
"`n==== RESULTS ===="
$global:Results
"REGRESSION: $pass passed, $fail failed"
