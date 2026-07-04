# Privacy Policy — Capoeira SSH

**Last updated: July 1, 2026**

Capoeira SSH ("the App") is a desktop SSH terminal client developed by Vicente Melo / Molho Ltda.
This policy explains what data the App handles and how.

## Summary

**Capoeira SSH does not collect, transmit, or sell any personal data to the developer.**
All application data is stored locally on your device. The only network connections the App
makes are (1) the SSH connections you explicitly configure to your own servers, and (2) an
anonymous check against GitHub's public API to see if a newer version is available.

## Data Stored Locally

All data created and used by the App is stored on your own computer, under `~/.capoeira/`
(or the equivalent user profile folder on Windows/macOS), and is never sent to the developer:

- **Saved sessions** (host, port, username, terminal preferences) — stored as local files.
- **Credential vault** — usernames and passwords you choose to save are encrypted with
  AES-256-GCM using a master password you set; the master password itself is never stored.
- **Known hosts** — SSH host key fingerprints, used the same way the `ssh` command line tool
  uses `~/.ssh/known_hosts`, to detect and warn about changed server identities.
- **Session logs** — if you enable logging for a session, the terminal output is saved to a
  local text file at a location you choose. This is a local, optional feature.
- **Application log** (`app.log`) — basic startup/error information for troubleshooting,
  stored locally and never transmitted.

None of the above is uploaded, synced, or made accessible to the developer or any third party
by the App.

## Network Connections

The App makes network connections only in these two cases:

1. **SSH connections you initiate.** When you connect to a server, the App opens a direct SSH
   connection between your computer and that server. The developer has no visibility into
   this connection, its contents, or the servers you connect to.
2. **Update check.** On startup, the App makes a single anonymous HTTPS request to GitHub's
   public API (`api.github.com`) to check whether a newer release is available. This request
   does not include any personal information, account identifiers, or usage data — it is a
   plain, unauthenticated HTTP GET, functionally identical to visiting a public webpage. As
   with any internet request, your IP address is visible to GitHub as the network operator,
   subject to [GitHub's own privacy policy](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
   This check can fail silently (e.g. offline use) without affecting the App.

The App does not include analytics, telemetry, crash reporters, or advertising SDKs of any kind.

## Third-Party Components

The App is built using the following open-source libraries, which run entirely on your device:

- [Eclipse SWT](https://www.eclipse.org/swt/) (EPL 2.0) — the graphical toolkit.
- [JSch (mwiede fork)](https://github.com/mwiede/jsch) (BSD-style) — the SSH protocol implementation.

Neither library transmits data to the developer or to third parties as used by this App.

## Children's Privacy

The App is a general-purpose IT administration tool and is not directed at children. It does
not knowingly collect data from anyone, including children, since it does not collect data at all.

## Changes to This Policy

If this policy changes, the updated version will be published at the same URL and in the
project's [CHANGELOG](CHANGELOG.md), with the "Last updated" date above revised accordingly.

## Contact

Questions about this policy or the App can be raised via
[GitHub Issues](https://github.com/vchaves123/capoeira-ssh/issues) or by contacting
Vicente Melo / Molho Ltda.
