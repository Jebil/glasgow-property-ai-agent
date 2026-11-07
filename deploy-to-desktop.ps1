$desktops = @(
    [Environment]::GetFolderPath('Desktop'),
    "$env:USERPROFILE\Desktop",
    "$env:USERPROFILE\OneDrive\Desktop"
) | Select-Object -Unique | Where-Object { $_ -and (Test-Path $_) }

foreach ($desktop in $desktops) {
    Write-Host "Deploying launcher to: $desktop"
    
    # 1. Copy run-app.ps1 and unblock
    $destPs1 = Join-Path $desktop "run-app.ps1"
    Copy-Item "C:\Dev\glasgow-property-ai-agent\run-app.ps1" -Destination $destPs1 -Force
    Unblock-File $destPs1 -ErrorAction SilentlyContinue
    
    # 2. Copy Start-Glasgow-Property-Agent.cmd (one-click batch launcher)
    $destCmd = Join-Path $desktop "Start-Glasgow-Property-Agent.cmd"
    Copy-Item "C:\Dev\glasgow-property-ai-agent\run-app.cmd" -Destination $destCmd -Force
    
    # 3. Create Windows Shortcut (.lnk)
    $wsh = New-Object -ComObject WScript.Shell
    $shortcutPath = Join-Path $desktop "Glasgow Property AI Agent.lnk"
    $shortcut = $wsh.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = "powershell.exe"
    $shortcut.Arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "C:\Dev\glasgow-property-ai-agent\run-app.ps1"'
    $shortcut.WorkingDirectory = "C:\Dev\glasgow-property-ai-agent"
    $shortcut.Description = "Launch Glasgow Property AI Agent"
    if (Test-Path "C:\Program Files\Google\Chrome\Application\chrome.exe") {
        $shortcut.IconLocation = "C:\Program Files\Google\Chrome\Application\chrome.exe,0"
    }
    $shortcut.Save()
    
    Write-Host "[OK] Desktop items successfully created at $desktop"
}
