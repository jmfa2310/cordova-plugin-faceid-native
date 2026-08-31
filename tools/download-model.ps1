$ErrorActionPreference = "Stop"

$PluginRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Target = Join-Path $PluginRoot "src\android\assets\mobilefacenet.tflite"
$Url = "https://github.com/hugocornellier/face_detection_tflite/raw/refs/heads/main/assets/models/mobilefacenet.tflite"

Write-Host "Downloading MobileFaceNet..."
Invoke-WebRequest -Uri $Url -OutFile $Target -UseBasicParsing

$Size = (Get-Item $Target).Length

if ($Size -lt 4000000) {
    Remove-Item $Target -Force -ErrorAction SilentlyContinue
    throw "Downloaded file is too small ($Size bytes)."
}

Write-Host ""
Write-Host "OK: $Target"
Write-Host "Size: $Size bytes"
Write-Host "Commit this model with the plugin in the private company Git repository."
