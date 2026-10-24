$ErrorActionPreference = 'Stop'
$root = 'd:\Code\richi_arise'
$log  = Join-Path $root 'rename_log.txt'
$out  = New-Object System.Collections.ArrayList

# 1) Java: translation keys + namespace/modid literals
$jx = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java
$changed = 0
foreach ($f in $jx) {
  $t = [System.IO.File]::ReadAllText($f.FullName)
  $o = $t
  $t = $t.Replace('"message.richi.', '"message.riichi_arise.')
  $t = $t.Replace('"gui.richi.', '"gui.riichi_arise.')
  $t = $t.Replace('"item_group.richi.', '"item_group.riichi_arise.')
  $t = $t.Replace('"item.richi.', '"item.riichi_arise.')
  $t = $t.Replace('fromNamespaceAndPath("richi"', 'fromNamespaceAndPath("riichi_arise"')
  $t = $t.Replace('NAMESPACE = "richi"', 'NAMESPACE = "riichi_arise"')
  $t = $t.Replace('@Mod("richii_arise")', '@Mod("riichi_arise")')
  $t = $t.Replace('MODID = "richii_arise"', 'MODID = "riichi_arise"')
  if ($t -ne $o) {
    [System.IO.File]::WriteAllText($f.FullName, $t, (New-Object System.Text.UTF8Encoding($false)))
    $changed++
    [void]$out.Add("JAVA $($f.Name)")
  }
}
[void]$out.Add("JAVA_CHANGED=$changed")

# 2) Move resource namespace folders
$assets = Join-Path $root 'src\main\resources\assets'
$data   = Join-Path $root 'src\main\resources\data'
if (Test-Path (Join-Path $assets 'richi')) { Move-Item (Join-Path $assets 'richi') (Join-Path $assets 'riichi_arise'); [void]$out.Add('MOVED assets/richi') }
if (Test-Path (Join-Path $data 'richi'))   { Move-Item (Join-Path $data 'richi')   (Join-Path $data 'riichi_arise');   [void]$out.Add('MOVED data/richi') }

# Merge leftover MCreator assets/richii_arise into the new namespace folder
$new = Join-Path $assets 'riichi_arise'
$old = Join-Path $assets 'richii_arise'
if (Test-Path $old) {
  $headSrc = Join-Path $old 'textures\head.png'
  if (Test-Path $headSrc) {
    $headDir = Join-Path $new 'textures'
    New-Item -ItemType Directory -Force -Path $headDir | Out-Null
    Move-Item $headSrc (Join-Path $headDir 'head.png') -Force
    [void]$out.Add('MOVED head.png')
  }
  Remove-Item -Recurse -Force $old
  [void]$out.Add('REMOVED assets/richii_arise')
}

# 3) Replace namespace string inside moved resource files (text only)
$targets = @($new, (Join-Path $data 'riichi_arise'))
$rc = 0
foreach ($dir in $targets) {
  Get-ChildItem $dir -Recurse -File | ForEach-Object {
    $full = $_.FullName
    if ($full -match '\.(png|jpg|mp3|ogg|wav|jar|zip)$') { return }
    $t = [System.IO.File]::ReadAllText($full)
    if ($t.Contains('richi')) {
      $t = $t.Replace('richi', 'riichi_arise')
      [System.IO.File]::WriteAllText($full, $t, (New-Object System.Text.UTF8Encoding($false)))
      $script:rc++
    }
  }
}
[void]$out.Add("RES_CHANGED=$rc")

$out | Set-Content -Path $log -Encoding UTF8
