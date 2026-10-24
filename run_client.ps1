$ErrorActionPreference = 'Continue'
$root = 'd:\Code\richi_arise'
$java = 'C:\Program Files\Java\latest\jdk-21\bin\java.exe'
if (-not (Test-Path $java)) { $java = 'D:\jdk21\bin\java.exe' }

$extra = @(
	(Join-Path $root 'build\moddev\artifacts\neoforge-21.1.232-merged.jar'),
	'C:\Users\User\.mcreator\gradle\caches\modules-2\files-2.1\net.neoforged\DevLaunch\1.0.2\2e0e9a67793d5bfdedee4f9ea00934dbf3f2e22d\DevLaunch-1.0.2.jar',
	(Join-Path $root 'build\jc')
)
$cp = (($extra | Where-Object { $_ -and (Test-Path $_) }) -join ';')
Set-Content -Path (Join-Path $root 'cp_len.txt') -Value ("CP_LEN=$($cp.Length)")

Set-Location $root
& $java -cp $cp "@$root\build\moddev\clientRunVmArgs_nojmx.txt" net.neoforged.devlaunch.Main "@$root\build\moddev\clientRunProgramArgs.txt" 2>&1 | Tee-Object -FilePath "$root\runclient_out.txt"
"EXIT=$LASTEXITCODE" | Add-Content (Join-Path $root 'compile_result.txt')
