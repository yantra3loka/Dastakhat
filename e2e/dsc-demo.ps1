# Dastakhat - DSC signing demonstration against the running backend, Mailpit and the local agent.
# Everything is TEST data: the agent runs in --mock mode with a software key.
$ErrorActionPreference = "Stop"

# 1. Trust the self signed certificate of the local agent (development only).
Add-Type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllCerts : ICertificatePolicy {
    public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) { return true; }
}
"@
[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllCerts

$Backend = "http://localhost:8080/api/v1"
$Agent   = "https://127.0.0.1:51515"
$Mailpit = "http://localhost:8025/api/v1"
$email   = "dsc-signer-$(Get-Date -Format yyyyMMddHHmmss)@example.test"

Write-Output "== 1. the local DSC agent =="
$health = Invoke-RestMethod -Uri "$Agent/health" -Headers @{ Origin = "http://localhost:5173" }
Write-Output ("   status={0} mock={1} token_present={2}" -f $health.status, $health.mock, $health.token_present)
$certs = Invoke-RestMethod -Uri "$Agent/certificates" -Headers @{ Origin = "http://localhost:5173" }
Write-Output ("   certificates on the agent: {0}" -f @($certs).Count)
$cert = @($certs)[0]
$cert | Get-Member -MemberType NoteProperty | Select-Object -ExpandProperty Name | ForEach-Object { Write-Output ("   field: " + $_) }

Write-Output "== 2. log in as the sender =="
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$login = Invoke-WebRequest -Uri "$Backend/auth/login" -Method Post -ContentType "application/json" `
    -Body '{"email":"sender@example.test","password":"ChangeMe!123"}' -WebSession $session -UseBasicParsing
$csrf = ($login.Content | ConvertFrom-Json).csrfToken
$cookies = ($session.Cookies.GetCookies("http://localhost:8080") |
        ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "; "
$headers = @{ "X-CSRF-TOKEN" = $csrf; "Cookie" = $cookies }
Write-Output ("   signed in, csrf token length {0}" -f $csrf.Length)

Write-Output "== 3. upload a document =="
$title = "DSC demonstration $(Get-Date -Format HHmmss)"
$uploadJson = curl.exe -s -X POST "$Backend/envelopes" `
    -H "X-CSRF-TOKEN: $csrf" -H "Cookie: $cookies" `
    -F "file=@E:\CyberF\DAY 6\e2e\fixtures\offer-letter.pdf" -F "title=$title"
$upload = $uploadJson | ConvertFrom-Json
$envelopeId = $upload.id
Write-Output ("   envelope {0} ({1} pages)" -f $envelopeId, $upload.pages)

Write-Output "== 4. add a DSC recipient and a signature field =="
$recipients = @{ recipients = @(@{ name = "Ravi Kumar"; email = $email; method = "dsc" }) } | ConvertTo-Json -Depth 5
$r = Invoke-RestMethod -Uri "$Backend/envelopes/$envelopeId/recipients" -Method Put -Body $recipients `
    -ContentType "application/json" -Headers $headers -WebSession $session
$recipientId = $r.recipients[0].id
$fields = @{ fields = @(@{ recipientId = $recipientId; type = "signature"; page = 1; x = 0.1; y = 0.6; w = 0.3; h = 0.08; required = $true }) } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "$Backend/envelopes/$envelopeId/fields" -Method Put -Body $fields `
    -ContentType "application/json" -Headers $headers -WebSession $session | Out-Null
Write-Output ("   recipient {0}" -f $recipientId)

Write-Output "== 5. send the envelope =="
Invoke-RestMethod -Uri "$Backend/envelopes/$envelopeId/send" -Method Post -Headers $headers -WebSession $session | Out-Null
Start-Sleep -Seconds 2

Write-Output "== 6. read the signing link from Mailpit =="
$messages = Invoke-RestMethod -Uri "$Mailpit/messages?limit=50"
$message = $messages.messages |
    Where-Object { (@($_.To) | ForEach-Object { $_.Address }) -contains $email } |
    Select-Object -First 1
if (-not $message) { throw "no mail was delivered to $email" }
$full = Invoke-RestMethod -Uri "$Mailpit/message/$($message.ID)"
$link = ([regex]::Match($full.Text, "https?://\S+/sign/\S+")).Value
$token = ($link -split "/sign/")[1]
Write-Output ("   signing link for {0}" -f $email)

Write-Output "== 7. the signer asks the backend for the hash to sign =="
$prepare = Invoke-RestMethod -Uri "$Backend/sign/$token/dsc/prepare" -Method Post `
    -Body (@{ certificatePem = $cert.pem } | ConvertTo-Json) -ContentType "application/json"
Write-Output ("   session {0}" -f $prepare.sessionId)
Write-Output ("   algorithm {0}" -f $prepare.signatureAlgorithm)
Write-Output ("   document sha256 {0}" -f $prepare.documentSha256)

Write-Output "== 8. the token signs the hash (private key never leaves the agent) =="
$signBody = @{
    to_be_signed_b64 = $prepare.toBeSignedBase64
    cert_label       = $cert.label
    doc_name         = $prepare.documentName
    doc_sha256       = $prepare.documentSha256
    pin              = "123456"
} | ConvertTo-Json
$signature = Invoke-RestMethod -Uri "$Agent/sign" -Method Post -Body $signBody -ContentType "application/json" `
    -Headers @{ Origin = "http://localhost:5173" }
Write-Output ("   signature {0} bytes (base64)" -f $signature.signatureBase64.Length)

Write-Output "== 9. the signature is submitted and embedded =="
$finish = Invoke-RestMethod -Uri "$Backend/sign/$token/dsc/finish" -Method Post `
    -Body (@{ sessionId = $prepare.sessionId; signatureBase64 = $signature.signatureB64 } | ConvertTo-Json) `
    -ContentType "application/json"
$finish | ConvertTo-Json

Write-Output "== 10. the final document and the audit trail =="
$detail = Invoke-RestMethod -Uri "$Backend/envelopes/$envelopeId" -Headers $headers -WebSession $session
Write-Output ("   envelope status {0}" -f $detail.status)
Write-Output ("   final sha256    {0}" -f $detail.finalSha256)
$out = "E:\CyberF\DAY 6\e2e\fixtures\dsc-final.pdf"
Invoke-WebRequest -Uri "$Backend/envelopes/$envelopeId/documents/final" -OutFile $out -Headers $headers -WebSession $session
Write-Output ("   downloaded {0} bytes to {1}" -f (Get-Item $out).Length, $out)
$audit = Invoke-RestMethod -Uri "$Backend/envelopes/$envelopeId/audit/verify" -Headers $headers -WebSession $session
Write-Output ("   audit chain intact={0} events={1}" -f $audit.intact, $audit.eventCount)
