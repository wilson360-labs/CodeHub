# =============================================================================
# kstore-restore.ps1 — Descifra un backup de keystore creado por
# kstore-backup.ps1, VERIFICANDO el HMAC antes de escribir el archivo.
#
# CodeHub · Wilson.E
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File scripts\kstore-restore.ps1 `
#     -InFile keys\release.jks.enc -OutFile apk\android\app\release.jks
#
# Password: -Password, $env:CODEHUB_KSTORE_PASS o prompt.
# =============================================================================
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$InFile,
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
$data = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $InFile))
$magic = [Text.Encoding]::ASCII.GetBytes('CHKS')
if ($data.Length -lt 76) { throw 'Archivo inválido (muy corto).' }
for ($i = 0; $i -lt 4; $i++) { if ($data[$i] -ne $magic[$i]) { throw 'No es un backup kstore (magic CHKS).' } }

$salt = New-Object byte[] 16; [Array]::Copy($data, 4,  $salt, 0, 16)
$iv   = New-Object byte[] 16; [Array]::Copy($data, 20, $iv,   0, 16)
$tag  = New-Object byte[] 32; [Array]::Copy($data, 36, $tag,  0, 32)
$ct   = New-Object byte[] ($data.Length - 68)
[Array]::Copy($data, 68, $ct, 0, $ct.Length)

$iter = 200000
$derive = New-Object System.Security.Cryptography.Rfc2898DeriveBytes($Password, $salt, $iter)
$key = $derive.GetBytes(32); $hmacKey = $derive.GetBytes(32)
$derive.Dispose()

$macData = New-Object System.Collections.Generic.List[byte]
$macData.AddRange($salt); $macData.AddRange($iv); $macData.AddRange($ct)
$hmac  = [System.Security.Cryptography.HMACSHA256]::new($hmacKey)
$calc  = $hmac.ComputeHash($macData.ToArray())
$hmac.Dispose()
$ok = $true
for ($i = 0; $i -lt 32; $i++) { if ($calc[$i] -ne $tag[$i]) { $ok = $false; break } }
if (-not $ok) { throw 'HMAC no coincide: password incorrecta o archivo alterado.' }

$aes = [System.Security.Cryptography.Aes]::Create()
$aes.Mode = [System.Security.Cryptography.CipherMode]::CBC
$aes.Padding = [System.Security.Cryptography.PaddingMode]::PKCS7
$aes.Key = $key; $aes.IV = $iv
$dec = $aes.CreateDecryptor()
$ms  = [System.IO.MemoryStream]::new($ct)
$cs  = New-Object System.Security.Cryptography.CryptoStream($ms, $dec, [System.Security.Cryptography.CryptoStreamMode]::Read)
$tmp = New-Object System.IO.MemoryStream
$cs.CopyTo($tmp)
$plain = $tmp.ToArray()
$cs.Dispose(); $aes.Dispose()

[IO.File]::WriteAllBytes($OutFile, $plain)
Write-Host "Restaurado OK (HMAC verificado): $OutFile ($($plain.Length) bytes)" -ForegroundColor Green