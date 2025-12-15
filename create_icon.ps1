
Add-Type -AssemblyName System.Drawing

$sourcePath = "app\src\main\res\drawable-nodpi\ic_gexu_logo_black.png"
$destPath = "ic_launcher_white_bg.png"

if (-not (Test-Path $sourcePath)) {
    Write-Host "Source file not found: $sourcePath"
    exit 1
}

$sourceImg = [System.Drawing.Image]::FromFile($sourcePath)
$width = $sourceImg.Width
$height = $sourceImg.Height

# Create a new bitmap with the same dimensions
$finalBitmap = New-Object System.Drawing.Bitmap $width, $height
$graphics = [System.Drawing.Graphics]::FromImage($finalBitmap)

# Fill with White
$graphics.Clear([System.Drawing.Color]::White)

# Draw the source image (Black Logo transparent) on top
$graphics.DrawImage($sourceImg, 0, 0, $width, $height)

$finalBitmap.Save($destPath, [System.Drawing.Imaging.ImageFormat]::Png)

$graphics.Dispose()
$finalBitmap.Dispose()
$sourceImg.Dispose()

Write-Host "Created $destPath"
