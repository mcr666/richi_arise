$ErrorActionPreference = 'Continue'
$root = 'd:\Code\richi_arise'
$javac = 'C:\Program Files\Java\latest\jdk-21\bin\javac.exe'
if (-not (Test-Path $javac)) { $javac = 'D:\jdk21\bin\javac.exe' }

$cpParts = @()
# AT-equivalent class overrides (javac does not apply accesstransformer.cfg) — must precede the merged jar
$atCls = Join-Path $root 'build\atcls'
if (Test-Path $atCls) { $cpParts += $atCls }
$cpParts += (Join-Path $root 'build\moddev\artifacts\neoforge-21.1.232-merged.jar')
$cpParts += (Get-Content (Join-Path $root 'build\moddev\clientLegacyClasspath.txt'))
$mahjong4jJar = 'C:\Users\User\.gradle\caches\modules-2\files-2.1\com.github.mahjong4j\mahjong4j\0.3.2\d65ba7f7daa96f5c43adf590aa3a5f781126bb49\mahjong4j-0.3.2.jar'
$jlayerJar = 'C:\Users\User\.gradle\caches\modules-2\files-2.1\com.googlecode.soundlibs\jlayer\1.0.1.4\b8df07ad72b482f7d6e6dee7fed669385e8dd92f\jlayer-1.0.1.4.jar'
$cpParts += $mahjong4jJar
$cpParts += $jlayerJar
# TLM (Touhou Little Maid) soft-dependency API jar (from MurmolMod local-maven)
$cpParts += 'D:\Code\MurmolMod\local-maven\maven\modrinth\touhou-little-maid\1.5.3-neoforge+mc1.21.1\touhou-little-maid-1.5.3-neoforge+mc1.21.1.jar'
$cp = ($cpParts | Where-Object { $_ -and (Test-Path $_) }) -join ';'

$outDir = Join-Path $root 'build\jc'
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$files = Get-ChildItem -Path (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$cpF = $cp -replace '\\','/'
$outF = $outDir -replace '\\','/'

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('-encoding UTF-8')
[void]$sb.AppendLine('-proc:none')
[void]$sb.AppendLine('-nowarn')
[void]$sb.AppendLine('-cp "' + $cpF + '"')
[void]$sb.AppendLine('-sourcepath "' + ((Join-Path $root 'src\main\java') -replace '\\','/') + '"')
[void]$sb.AppendLine('-d "' + $outF + '"')
foreach ($f in $files) { [void]$sb.AppendLine('"' + ($f -replace '\\','/') + '"') }

$argFile = Join-Path $root 'javac.args'
[System.IO.File]::WriteAllText($argFile, $sb.ToString(), [System.Text.Encoding]::ASCII)

"COUNT=$($files.Count)" | Set-Content (Join-Path $root 'compile_result.txt')

# Copy fresh resources (mods.toml, mixins json, accesstransformer.cfg, assets, data) into the mod output folder
Copy-Item -Path (Join-Path $root 'src\main\resources\*') -Destination $outDir -Recurse -Force

# Unpack bundled libraries into the mod output folder (same as the Gradle jar task: zipTree mahjong4j/jlayer).
# Without this the mod classes throw NoClassDefFoundError at runtime (org.mahjong4j.tile.Tile).
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($lib in @($mahjong4jJar, $jlayerJar)) {
	if (-not (Test-Path $lib)) { continue }
	$zip = [System.IO.Compression.ZipFile]::OpenRead($lib)
	try {
		foreach ($entry in $zip.Entries) {
			if ([string]::IsNullOrEmpty($entry.Name)) { continue }
			if ($entry.FullName -eq 'META-INF/MANIFEST.MF') { continue }
			$dest = Join-Path $outDir ($entry.FullName -replace '/', '\')
			$destDir = Split-Path $dest -Parent
			if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Force -Path $destDir | Out-Null }
			[System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
		}
	} finally {
		$zip.Dispose()
	}
}

& $javac "@$argFile" 1> (Join-Path $root 'javac_out.txt') 2> (Join-Path $root 'javac_err.txt')
"EXIT=$LASTEXITCODE" | Add-Content (Join-Path $root 'compile_result.txt')
$n = (Get-ChildItem -Path $outDir -Recurse -Filter *.class -ErrorAction SilentlyContinue).Count
"CLASSES=$n" | Add-Content (Join-Path $root 'compile_result.txt')
