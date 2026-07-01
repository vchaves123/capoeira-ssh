# Code Signing — 14bis SSH

The Windows installer is signed automatically by GitHub Actions when a code-signing
certificate is configured as repository secrets. Without a certificate the build
succeeds but the installer is unsigned (Windows Smart App Control will block it).

## Option 1 — SignPath Foundation (free for open-source)

[SignPath.io](https://signpath.io/code-signing-for-open-source/) provides free
code-signing certificates for open-source projects. Requirements:
- Public GitHub repository with an OSI-approved license (GPL v3 ✓)
- Apply at https://signpath.io/code-signing-for-open-source/

Their GitHub Actions integration can replace the signing step in
`.github/workflows/release.yml` once approved.

## Option 2 — Own PFX certificate (any CA)

1. Obtain a code-signing certificate (OV or EV) from Sectigo, DigiCert, etc.
2. Export it as a `.pfx` file (PKCS#12, includes private key).
3. Convert to base64:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("cert.pfx")) | clip
   ```
4. Add two secrets to the GitHub repository
   (**Settings → Secrets and variables → Actions → New repository secret**):

   | Secret name              | Value                              |
   |--------------------------|------------------------------------|
   | `CODE_SIGN_CERT_BASE64`  | base64 string from step 3          |
   | `CODE_SIGN_PASSWORD`     | PFX password                       |

5. The next release will automatically sign `14bis-SSH-windows.exe`.

## Verifying a signed installer

```powershell
Get-AuthenticodeSignature .\14bis-SSH-windows.exe | Select-Object Status, SignerCertificate
```

Expected output: `Status = Valid`, certificate subject shows "Molho Ltda."

## Without a certificate (development / testing)

Unblock the installer manually:
1. Right-click the `.exe` → Properties → check **Unblock** → OK  
   *(only works if Smart App Control is in Evaluation mode, not Enforcement)*
2. Or: **Windows Security → App & browser control →
   Smart App Control settings → Off**
   (cannot be re-enabled without reinstalling Windows)
