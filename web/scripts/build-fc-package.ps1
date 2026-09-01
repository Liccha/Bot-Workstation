param(
  [string]$OutputPath = ".fc-build/songbot-domestic-api.zip"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $repo ".fc-build"
$stage = Join-Path $buildRoot "stage"
$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputPath)) { $OutputPath } else { Join-Path $repo $OutputPath }

if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stage "fc") -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $repo "fc/index.js") -Destination (Join-Path $stage "fc/index.js")
Copy-Item -LiteralPath (Join-Path $repo "fc-entry.js") -Destination $stage
Copy-Item -LiteralPath (Join-Path $repo "api") -Destination $stage -Recurse
Copy-Item -LiteralPath (Join-Path $repo "package.json") -Destination $stage
Copy-Item -LiteralPath (Join-Path $repo "package-lock.json") -Destination $stage

Push-Location $stage
try {
  npm ci --omit=dev --ignore-scripts --no-audit --no-fund | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
} finally {
  Pop-Location
}

$outputDir = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
if (Test-Path -LiteralPath $resolvedOutput) { Remove-Item -LiteralPath $resolvedOutput -Force }
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $resolvedOutput -CompressionLevel Optimal

$archive = Get-Item -LiteralPath $resolvedOutput
Write-Output ("FC package ready: {0} ({1} bytes)" -f $archive.FullName, $archive.Length)
