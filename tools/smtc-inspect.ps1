<#
.SYNOPSIS
    Shows the Windows media sessions (SMTC) the way media flyouts see them.

.DESCRIPTION
    Verifies the PC client without depending on a particular flyout: lists every session with
    its title, artist, whether it has album art, its state, position and shuffle button. With
    -ToggleShuffle it presses the shuffle button of the SashaBridge session, as a flyout that
    offered the button would.

    Needs Windows PowerShell 5.1 (powershell.exe), because it uses WinRT.
    See docs/verificacion.md.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\smtc-inspect.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\smtc-inspect.ps1 -ToggleShuffle
#>
param([switch]$ToggleShuffle)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -ne 'Desktop') {
    throw 'This script needs Windows PowerShell 5.1 (powershell.exe), not PowerShell 7.'
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } |
    Select-Object -First 1

# Waits for a WinRT async operation and returns its result.
function Await($operation, [Type]$resultType) {
    $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $task.Wait(-1) | Out-Null
    $task.Result
}

[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$current = $manager.GetCurrentSession()

foreach ($session in $manager.GetSessions()) {
    $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    $info = $session.GetPlaybackInfo()
    $timeline = $session.GetTimelineProperties()
    [pscustomobject]@{
        App           = $session.SourceAppUserModelId
        Current       = ($null -ne $current -and $session.SourceAppUserModelId -eq $current.SourceAppUserModelId)
        Status        = $info.PlaybackStatus
        Title         = $props.Title
        Artist        = $props.Artist
        HasCover      = ($null -ne $props.Thumbnail)
        Position      = $timeline.Position
        Duration      = $timeline.EndTime
        ShuffleButton = $info.Controls.IsShuffleEnabled
        ShuffleActive = $info.IsShuffleActive
    }
}

if ($ToggleShuffle) {
    $bridge = $manager.GetSessions() | Where-Object { $_.SourceAppUserModelId -match 'sashabridge' } | Select-Object -First 1
    if ($null -eq $bridge) { throw 'No SashaBridge session. Is the PC client running?' }
    $target = -not [bool]$bridge.GetPlaybackInfo().IsShuffleActive
    $accepted = Await ($bridge.TryChangeShuffleActiveAsync($target)) ([bool])
    Write-Output "Shuffle pressed (requested: $target, accepted by Windows: $accepted). The new mode arrives with the phone's next state."
}
