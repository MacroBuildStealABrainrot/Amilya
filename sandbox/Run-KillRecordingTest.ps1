# SANDBOX ONLY. Simulates Android killing the app in the middle of a recording, then reopens it.
# Run AFTER installing the sandbox app (RUN-ON-YOUR-PC.md Step 5) and starting a made-up-voice recording in the phone app.
# It only force-stops the SANDBOX app (com.woundcopilot.android.sandbox). It never touches your release or debug app.
$pkg = 'com.woundcopilot.android.sandbox'
$adb = 'adb'
if (-not (& $adb shell pm list packages $pkg)) { Write-Host "The sandbox app is not installed. Do Step 5 first." -ForegroundColor Red; exit 1 }
Write-Host "Make sure the sandbox app is RECORDING now (made-up voice, at least 60 seconds)." -ForegroundColor Cyan
Read-Host "Press Enter to kill the app now"
$stamp = Get-Date -Format HH:mm:ss
Write-Host "[$stamp] Killing the app (like Android reclaiming memory)..."
& $adb shell am kill $pkg            # same effect as the system killing a background process
& $adb shell am force-stop $pkg      # make sure it is really gone
Start-Sleep -Seconds 3
Write-Host "Reopening the app..."
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "Now look at the phone: the interrupted recording should be listed as saved and playable, with the audio up to about the kill moment." -ForegroundColor Green
Write-Host "Write down: the time you pressed Enter, how long the recording had run, and the length of the recovered audio."
