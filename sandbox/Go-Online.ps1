# Restores the USB link so the SANDBOX app can reach the PC test server again.
adb reverse tcp:18766 tcp:18766
Write-Host 'Phone is ONLINE to the test server again.' -ForegroundColor Green
