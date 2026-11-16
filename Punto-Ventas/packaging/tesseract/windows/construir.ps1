<#
  Compila Tesseract para el instalador de Windows con vcpkg y arma la carpeta que va en
  <app>\ocr\tesseract (la que busca ocr/LocalizadorTesseract):

      <Destino>\bin\tesseract.exe        un solo ejecutable estático (sin DLLs ni runtime de Visual C++)
      <Destino>\LICENSE-tesseract.txt    Apache-2.0
      <Destino>\licencias\<puerto>.txt   licencia de cada biblioteca enlazada (leptonica, libpng, libjpeg-turbo, ...)
      <Destino>\vcpkg.txt                commit de vcpkg y versiones exactas de lo compilado

  Los idiomas (tessdata\spa, eng) los pone después packaging/tesseract/bajar-tessdata.sh, y la comprobación de que
  arranca y lee el ticket de muestra la hace packaging/tesseract/comprobar.sh (ver .github/workflows/tesseract-windows.yml).

  Uso (en Windows con Visual Studio 2022 o sus Build Tools, como el runner windows-latest):
      pwsh packaging/tesseract/windows/construir.ps1 -Arquitectura x64 -Destino build/ocr-tesseract-x64
      pwsh packaging/tesseract/windows/construir.ps1 -Arquitectura x86 -Destino build/ocr-tesseract-x86

  Reproducible: vcpkg fijo en un commit (sus puertos fijan versión y SHA-512 de cada código fuente) y solo las
  funciones mínimas: tesseract sin curl ni libarchive y leptonica sin gif/webp/openjpeg (el programa le pasa la imagen
  en PGM por stdin; JPEG/PNG/TIFF quedan para las comprobaciones). Tarda ~10-20 min sin caché.
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('x64', 'x86')][string]$Arquitectura,
    [Parameter(Mandatory = $true)][string]$Destino,
    [string]$CarpetaVcpkg = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# vcpkg 2026.10.16 (tesseract 5.5.4, leptonica 1.87.0: las mismas versiones que la de Homebrew del instalador de macOS).
# Para subirlo: cambiar el commit (git ls-remote --tags https://github.com/microsoft/vcpkg) y probar en el CI.
$VcpkgCommit = '9eec002df1cd109f8f104cd966a763daf9751489'
$Triplet = "$Arquitectura-windows-static-rel"
$Aqui = $PSScriptRoot

if (-not $CarpetaVcpkg) {
    $base = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
    $CarpetaVcpkg = Join-Path $base 'vcpkg-caja-libre'
}

function Ejecutar([string]$Programa, [string[]]$Argumentos) {
    Write-Host ">> $Programa $($Argumentos -join ' ')"
    & $Programa @Argumentos
    if ($LASTEXITCODE -ne 0) { throw "Falló ($LASTEXITCODE): $Programa $($Argumentos -join ' ')" }
}

# 1. vcpkg en el commit fijo (solo ese commit, sin historial)
if (-not (Test-Path (Join-Path $CarpetaVcpkg '.git'))) {
    New-Item -ItemType Directory -Force -Path $CarpetaVcpkg | Out-Null
    Ejecutar git @('-C', $CarpetaVcpkg, 'init', '--quiet')
}
Ejecutar git @('-C', $CarpetaVcpkg, 'fetch', '--quiet', '--depth', '1', 'https://github.com/microsoft/vcpkg.git', $VcpkgCommit)
Ejecutar git @('-C', $CarpetaVcpkg, 'checkout', '--quiet', '--force', '--detach', $VcpkgCommit)
$vcpkg = Join-Path $CarpetaVcpkg 'vcpkg.exe'
if (-not (Test-Path $vcpkg)) {
    # descarga la herramienta vcpkg.exe que fija ese commit (scripts/vcpkg-tool-metadata.txt, con su SHA-512)
    Ejecutar (Join-Path $CarpetaVcpkg 'bootstrap-vcpkg.bat') @('-disableMetrics')
}
# que no se use el vcpkg que trae el runner
$env:VCPKG_ROOT = $CarpetaVcpkg
$env:VCPKG_DISABLE_METRICS = '1'

# 2. Compilar (modo clásico: se corre desde la carpeta de vcpkg, sin vcpkg.json)
Push-Location $CarpetaVcpkg
try {
    Ejecutar $vcpkg @('install', 'tesseract[core]', 'leptonica[core]', '--triplet', $Triplet,
        '--overlay-triplets', (Join-Path $Aqui 'triplets'), '--clean-after-build')
    $lista = & $vcpkg list --triplet $Triplet 2>$null
} finally {
    Pop-Location
}

# 3. Armar la carpeta
$instalado = Join-Path $CarpetaVcpkg "installed\$Triplet"
$herramientas = Join-Path $instalado 'tools\tesseract'
$exe = Join-Path $herramientas 'tesseract.exe'
if (-not (Test-Path $exe)) { throw "vcpkg no dejó $exe" }

if (Test-Path $Destino) { Remove-Item -Recurse -Force $Destino }
$bin = Join-Path $Destino 'bin'
$licencias = Join-Path $Destino 'licencias'
New-Item -ItemType Directory -Force -Path $bin, $licencias | Out-Null
Copy-Item $exe $bin
# Con el triplet estático no debería haber DLLs; si una versión futura de vcpkg deja alguna, se copia junto al exe.
foreach ($dll in Get-ChildItem $herramientas -Filter *.dll -ErrorAction SilentlyContinue) {
    Write-Host "::warning::DLL junto a tesseract.exe: $($dll.Name) (se incluye)"
    Copy-Item $dll.FullName $bin
}
foreach ($carpeta in Get-ChildItem (Join-Path $instalado 'share') -Directory) {
    $copyright = Join-Path $carpeta.FullName 'copyright'
    if ((Test-Path $copyright) -and -not $carpeta.Name.StartsWith('vcpkg-')) {
        Copy-Item $copyright (Join-Path $licencias "$($carpeta.Name).txt")
    }
}
Copy-Item (Join-Path $instalado 'share\tesseract\copyright') (Join-Path $Destino 'LICENSE-tesseract.txt')
@("vcpkg $VcpkgCommit, triplet $Triplet", '') + $lista | Set-Content -Encoding utf8 (Join-Path $Destino 'vcpkg.txt')

# 4. Arquitectura del ejecutable (campo Machine de la cabecera PE: 0x14C = x86, 0x8664 = x64)
$bytes = [System.IO.File]::ReadAllBytes((Join-Path $bin 'tesseract.exe'))
$pe = [System.BitConverter]::ToInt32($bytes, 0x3C)
$machine = [System.BitConverter]::ToUInt16($bytes, $pe + 4)
$esperado = if ($Arquitectura -eq 'x86') { 0x14C } else { 0x8664 }
if ($machine -ne $esperado) { throw ("tesseract.exe es Machine=0x{0:X}; se esperaba 0x{1:X}" -f $machine, $esperado) }

$total = (Get-ChildItem $Destino -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ("Tesseract {0}: {1:N1} MB en {2} (Machine 0x{3:X})" -f $Arquitectura, ($total / 1MB), $Destino, $machine)
Get-ChildItem $Destino -Recurse -File | ForEach-Object { "{0,12:N0}  {1}" -f $_.Length, $_.FullName.Substring((Resolve-Path $Destino).Path.Length) }
