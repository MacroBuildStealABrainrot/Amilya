# Makes the SANDBOX app "offline": cuts the USB link between the phone and the PC test server.
# (Airplane mode does not work for this, because the sandbox app only talks to the PC through the USB link.)
adb reverse --remove tcp:18766
Write-Host 'Phone is now OFFLINE from the test server.' -ForegroundColor Yellow
