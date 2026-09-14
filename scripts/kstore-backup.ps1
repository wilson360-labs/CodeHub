# =============================================================================
# kstore-backup.ps1 — Cifra un keystore Android para guardarlo fuera de GitHub.
#
# CodeHub · Wilson.E
# Cifrado: AES-256-CBC (PKCS7) + PBKDF2-SHA1 (200 000 iteraciones) +
#          HMAC-SHA256 (encrypt-then-MAC). Formato:
#          "CHKS" | salt(16) | iv(16) | tag(32) | cifrado
# Password: se toma de -Password, de $env:CODEHUB_KSTORE_PASS o se pide.
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File scripts\kstore-backup.ps1 `
#     -KeyStore apk\android\app\release.jks -OutFile keys\release.jks.enc
#
# Backup recomendado (3 copias):
#   1. keys\release.jks.enc  (se commitea — es ilegible sin la password)
#   2. Una copia en tu gestor de contraseñas como adjunto (con la password)
#   3. Una copia offline (USB cifrado) junto con la password
# =============================================================================
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$KeyStore,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [string]$Password = $env:CODEHUB_KSTORE_PASS
)

$ErrorActionPreference = 'Stop'
if (-not $Password) {
  $sec = Read-Host 'Password del backup' -AsSecureString
  $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
  $Password = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
  [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
if (-not $Password -or $Password.Length -lt 16) {
  throw 'Usa una password de 16+ caracteres (ruta en tu gestor de contraseñas).'
}
if (-not (Test-Path -LiteralPath $KeyStore)) { throw "No existe: $KeyStore" }
if (-not (Test-Path -LiteralPath (Split-Path $OutFile))) {
  New-Item -ItemType Directory -Path (Split-Path $OutFile) -Force | Out-Null
}

$plain = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $KeyStore))

$rng  = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$salt = New-Object byte[] 16; $rng.GetBytes($salt)
$iv   = New-Object byte[] 16; $rng.GetBytes($iv)

$iter = 200000
$derive = New-Object System.Security.Cryptography.Rfc2898DeriveBytes($Password, $salt, $iter)
$key = $derive.GetBytes(32); $hmacKey = $derive.GetBytes(32)
$derive.Dispose()

$aes = [System.Security.Cryptography.Aes]::Create()
$aes.Mode  = [System.Security.Cryptography.CipherMode]::CBC
$aes.Padding = [System.Security.Cryptography.PaddingMode]::PKCS7
$aes.Key = $key; $aes.IV = $iv
$enc = $aes.CreateEncryptor()
$ms  = New-Object System.IO.MemoryStream
$cs  = New-Object System.Security.Cryptography.CryptoStream($ms, $enc, [System.Security.Cryptography.CryptoStreamMode]::Write)
$cs.Write($plain, 0, $plain.Length); $cs.FlushFinalBlock()
$ct  = $ms.ToArray()
$cs.Dispose(); $aes.Dispose()

# Encrypt-then-MAC sobre salt||iv||ct
$macData = New-Object System.Collections.Generic.List[byte]
$macData.AddRange($salt); $macData.AddRange($iv); $macData.AddRange($ct)
$hmac  = [System.Security.Cryptography.HMACSHA256]::new($hmacKey)
$tag   = $hmac.ComputeHash($macData.ToArray())
$hmac.Dispose()

$magic = [Text.Encoding]::ASCII.GetBytes('CHKS')
$out = New-Object System.Collections.Generic.List[byte]
$out.AddRange($magic); $out.AddRange($salt); $out.AddRange($iv); $out.AddRange($tag); $out.AddRange($ct)
[IO.File]::WriteAllBytes($OutFile, $out.ToArray())

Write-Host "Backup cifrado OK: $OutFile ($($out.Count) bytes)" -ForegroundColor Green
Write-Host "Guarda la password en tu gestor (CODEHUB_KSTORE_PASS). Nunca en el repo." -ForegroundColor Yellow