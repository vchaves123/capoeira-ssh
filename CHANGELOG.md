# Changelog

All notable changes to 14bis SSH are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.3.1] — 2026-07-02

### Fixed
- No-JRE bundle: `run.bat` launched the app with `java.exe`, leaving a console window open (blank,
  doing nothing) for the app's entire lifetime. Now launches with `javaw.exe` (falling back to
  `java.exe` only if `javaw` isn't found), so the console window closes immediately.
- Renamed the bundle to `14bis-SSH-nojre-multiplatform-<version>.tar.gz` to make clear it targets
  every supported OS/architecture, not just one.

---

## [1.3.0] — 2026-07-02

### Added
- **No-JRE bundle** (`14bis-SSH-nojre-<version>.tar.gz`): a single, platform-independent archive
  for users who want to run 14bis SSH on a Java runtime they already manage (21+), instead of the
  bundled-JRE installers/portable builds. Unlike those (one build per OS/arch, each with SWT
  compiled in), this bundle ships the application as a thin jar plus every supported SWT native
  build (Windows/Linux/macOS × x86_64/aarch64) under `lib/swt/`, and `run.bat`/`run.sh` scripts
  that detect the current OS/architecture and put only the matching SWT jar on the classpath. One
  download works everywhere Java 21+ is available.

---

## [1.2.1] — 2026-07-02

### Fixed
- **Windows launcher/desktop icon**: the custom app icon was not being applied to the Windows
  `.exe` — Explorer, the desktop shortcut, and the taskbar all showed the generic default jpackage
  launcher icon. The `.ico` was built with PNG-compressed data for every size, but the Windows
  shell only decodes PNG-in-ICO at 256×256 and requires classic BMP/DIB for the small sizes
  (16/32/48); jpackage copies the icon bytes into the launcher verbatim without re-encoding, so the
  small icons were unrenderable and Windows fell back to the default. `IconExporter` now generates
  the `.ico` directly in Java with BMP/DIB entries for 16/32/48 (correct `BITMAPINFOHEADER` with a
  doubled height and AND mask) and PNG for 256, replacing the hand-rolled PowerShell conversion in
  the release workflow. Verified end-to-end locally against a `jpackage` app-image.

> If Explorer still shows the old icon after installing 1.2.1, clear the Windows icon cache once
> (`ie4uinit.exe -show`) or recreate the shortcut — the embedded exe icon is now correct.

---

## [1.2.0] — 2026-07-02

### Added
- **Configuration Setting dialog**: logging, terminal appearance, terminal type and Backspace key
  are now grouped into a single reusable dialog with three scopes — global defaults (Home tab
  → "Configuration Setting", applied to every new session), per-session (Session dialog →
  "Configuration Setting…"), and per-terminal (tab context menu → "Settings…", affecting only
  that live tab without touching the saved session).
- **Redesigned session authentication**: the Username field is now a single editable combo — type
  a username, or pick a previously saved vault credential from the dropdown, which auto-fills and
  greys out the dependent fields. A "Use private key" checkbox reveals a key-file browser, and the
  password field doubles as the key passphrase. A "Save Credential…" button stores either a
  password- or key-based credential into the vault for reuse across sessions.
- **Private-key credentials in the vault**: the Credential Manager now supports key-file
  credentials (with an optional passphrase), not just user/password entries.
- **First-connect password pre-fill**: a password typed while creating a new session pre-fills the
  first Connect dialog and auto-connects, so it is no longer asked for twice. The value is kept
  only in memory (a `char[]` zeroed after use) and never written to disk.
- **Editable log file name**: when logging is enabled the file name is pre-filled and editable in
  the exact `<timestamp>_<name>.log` format that will be written.
- Per-version CHANGELOG section is now included automatically in the GitHub release notes.

### Changed
- The terminal tab context menu is simplified: the separate "Terminal Appearance…" and
  "Log Settings…" items are replaced by one "Settings…" entry; logging is toggled by the "Enable"
  checkbox inside it rather than a menu item.
- The Home tab's "Default Appearance" button is now "Configuration Setting" and edits the global
  defaults used for new sessions.
- The credential vault is only unlocked when the user actually opens the Username dropdown to pick
  a saved credential — not merely when focusing the field to type.

### Fixed
- Security audit of the pre-release changes (build 85) — 4 findings fixed: `TerminalEmulator`
  `insertChars` (`CSI @`) and `scrollRegion` (`CSI S`/`T`) are now clamped so a malicious/MITM
  server can't crash or freeze the session; the release workflow's GitHub Actions script-injection
  surface is hardened (untrusted values passed via `env:` + tag charset validation); all Actions
  are pinned to full commit SHAs.
- Security audit of the authentication/settings redesign (build 97) — 5 findings fixed: the
  manually-typed password `char[]` is captured only after a successful save and only on the
  new-session path (no un-zeroed copy leaks on the edit or failed-save paths); editing a
  vault-linked session while the vault stays locked now preserves the credential link instead of
  silently downgrading to manual auth; `ConfigurationSettingsDialog` disposes its swatch `Color`
  objects on close (native handle leak); the Username-combo dropdown-unlock zone is DPI-aware; and
  `SessionDefaults` clamps `backspaceCode` to DEL/BS on load.

---

## [1.1.0] — 2026-07-01

### Added
- **Session group renaming**: right-click a group in the session tree → "Rename Group" moves
  its directory (and all sessions inside it) to the new name.
- **Windows portable build**: a `.zip` (no installer, no admin rights required) alongside the
  existing `.exe` — unzip and run `14bis SSH.exe` directly.
- **Monospace font selection**: "Terminal Appearance" now has a font dropdown (global default
  and per-session), listing only monospace fonts actually installed on the system.

### Changed
- Default terminal font size changed from 14pt to 12pt.
- Default text colour changed from light gray to classic amber phosphor (#FFB000).
- About dialog now also credits the bundled Java runtime (Eclipse Temurin/OpenJDK).
- Removed references to SignPath Foundation from the README/SIGNING docs after the free
  code-signing application was declined (project too new for their adoption requirements);
  releases remain unsigned for now.

---

## [1.0.9] — 2026-07-01

### Added
- **Import sessions**: new "Import..." button on the Home tab session tree scans PuTTY sessions
  (Windows registry, or `~/.putty/sessions` on Linux/macOS) and/or a browsed `MobaXterm.ini` file,
  listing found sessions with checkboxes to pick what to import. Only name/host/port/username are
  imported — never passwords. MobaXterm's undocumented session-string format is best-effort
  parsed, with encoding auto-detection (UTF-8/UTF-16/Windows-1252) and support for bookmark
  subfolders (`[Bookmarks_1]`, `[Bookmarks_2]`, ...).
- Session tree now supports **dragging multiple selected sessions** at once into another group.
- Release asset filenames now include the version (e.g. `14bis-SSH-windows-1.0.9.exe`).
- Linux releases now also ship `.rpm` (RHEL/OEL/AlmaLinux/Rocky Linux/SUSE/etc.) and a portable
  `.tar.gz` build alongside the existing `.deb`.

### Fixed
- `reload()` used to force-expand every group in the session tree whenever none was currently
  expanded, silently undoing a deliberate "collapse all". Now the actual expanded/collapsed state
  is preserved across session create/edit/move actions (only the very first load defaults open).
- The "+ Session"/"+ Group"/refresh buttons were nearly invisible on Linux (GTK ignored the
  app's dark-panel colours on native toolbar buttons); replaced with plain `Button` widgets.

---

## [1.0.8] — 2026-07-01

### Fixed
- Windows/macOS release assets were named `14bis.SSH-<version>.exe`/`.dmg` (jpackage's default
  naming) instead of the documented `14bis-SSH-windows.exe`/`14bis-SSH-macos.dmg`. Now renamed
  after packaging, consistent with the Linux asset naming.
- The "+ Session"/"+ Group"/refresh toolbar buttons on the session tree panel were nearly
  invisible on Linux (GTK draws native toolbar button labels using the system theme, ignoring the
  app's dark-panel colours). Replaced with plain `Button` widgets, which render with proper
  contrast on every platform.

---

## [1.0.7] — 2026-07-01

### Added
- **Linux `.rpm` package**: built via `jpackage --type rpm` alongside the existing `.deb`, for
  RHEL, Oracle Linux, AlmaLinux, Rocky Linux, SUSE/openSUSE, and other RPM-based distributions.
- **Linux portable `.tar.gz`**: a self-contained, JRE-bundled build (`jpackage --type app-image`)
  that can be unpacked anywhere and run directly via `bin/ssh-14bis`, with no installation or
  package manager required.

---

## [1.0.6] — 2026-07-01

### Fixed
Third full security audit (6 dimensions: credential vault, network/SSH, filesystem, terminal
emulator, supply chain/CI, UI input validation), each finding adversarially verified by 3
independent reviewers. Eight confirmed issues, all fixed:

- **HIGH — `CredentialStore.unlock()`**: the whole decrypted vault (every saved password) was
  materialized as an immutable `String` that can never be zeroed and would linger on the heap
  until GC, recoverable via a heap/core dump. `deserialize()` now works directly on `char[]`,
  extracting only non-secret label/username fields to short-lived `String`s.
- **HIGH — `CredentialStore.persist()`**: the same issue on the write path — `serialize()` built
  one unzeroed `String`/`StringBuilder` containing every password on every save. Now serializes
  into a `StringBuilder` that is explicitly wiped, then encodes to a `byte[]` that is zeroed after
  encryption.
- **HIGH — `TerminalEmulator.deleteChars()`**: a remote server could crash an active session with
  a single ~8-byte escape sequence (`CSI P` with a count larger than the terminal width), driving
  an array index negative and throwing an uncaught exception. Now clamped to the remaining columns.
- **HIGH — `TerminalTab.openLogFile()`**: the log-directory containment check was purely lexical,
  so a symlink/NTFS junction planted under the home directory (e.g. via an unvalidated `logDir` in
  an imported session file) could redirect log writes outside the intended sandbox. Now re-verified
  against the real (symlink-resolved) path, falling back to the default log directory otherwise.
- **MEDIUM — `SessionStorage.sanitize()`**: a group named `"."` or `".."` resolved outside the
  intended `sessions/<group>` directory into `~/.14bis` itself. Now falls back to a safe name.
- **MEDIUM — `TerminalEmulator` OSC buffer**: a malicious server that never sends the OSC/DCS
  terminator could grow the payload buffer without bound toward `OutOfMemoryError`. Capped at 8KB.
- **MEDIUM — `TerminalEmulator.insertLines()`/`deleteLines()`**: `CSI L`/`M` accepted an unbounded
  repeat count, freezing the whole application UI (SWT is single-threaded) on a single short escape
  sequence. Clamped to the scroll-region size.
- **MEDIUM — `TerminalTab.stripAnsi()`**: the log sanitizer only recognized 7-bit `ESC` as an
  escape introducer, letting 8-bit C1 control sequences (which the terminal emulator itself
  interprets as CSI/OSC) pass through unfiltered into the plaintext session log. Added
  UTF-8-continuation-aware C1 handling so genuine multi-byte characters aren't corrupted.

---

## [1.0.5] — 2026-07-01

### Added
- **Home tab**: the former "Sessions" tab is renamed to "Home", always stays first, and can no
  longer be reordered past — no other tab can be dragged before it.
- **Session tab renaming**: right-click a session tab → "Rename Tab..." to give it a custom title.
- **Activity feedback**: background tab activity now blinks the tab title in bold blue (settling
  to solid bold blue once traffic stops) instead of a small dot icon; the tab title is pre-padded
  so it no longer grows/shrinks when the font toggles bold. Disconnected sessions show a fixed
  bold red title, including while selected.
- **Close confirmation**: clicking the close (×) button on a session tab now asks for confirmation
  before disconnecting/closing it.
- **Per-session terminal type**: editable combo (`xterm-256color`, `xterm`, `vt100`, `ansi`,
  `linux`) sent as the PTY type — useful for AIX servers, which expect a plain `xterm`.
- **Per-session Backspace key**: choose `DEL (0x7F)` (default) or `BS (0x08)` — AIX servers use a
  different Backspace code than Linux.
- **Update check**: on startup, the app silently checks GitHub for a newer release; if found, a
  "New version available — Download" link appears on the Home tab.
- **About dialog**: now shows the real running version/build/date (previously hardcoded
  "Version 1.0.0"), credits third-party libraries (Eclipse SWT, JSch — mwiede fork) with clickable
  links, and includes the project copyright line.
- The build version label on the Home tab is now a clickable link to that release's GitHub page.

### Fixed
- The tree-view expand/collapse triangle for session groups was nearly invisible on the dark
  background (Windows Explorer theme assumes a light background); it now uses the classic +/- box.
- Removed a leftover internal hostname reference from the terminal appearance preview text.

---

## [1.0.4] — 2026-07-01

### Added
- Windows installer now detects a previously installed version and offers to uninstall it before
  installing the new one (WiX `UpgradeCode` / `--win-upgrade-uuid`).
- Automatic Windows code-signing via GitHub Actions: when the `CODE_SIGN_CERT_BASE64` and
  `CODE_SIGN_PASSWORD` repository secrets are present the installer is signed with `signtool.exe`
  using SHA-256 and a Sectigo RFC 3161 timestamp.
- `SIGNING.md` — instructions for configuring a PFX certificate or applying for a free
  [SignPath Foundation](https://signpath.io/code-signing-for-open-source/) certificate.
- README: "Code signing" section mentioning SignPath Foundation.

### Fixed
- `run.bat`: JAR path resolved by glob **after** `mvn clean package` — the previous version
  resolved the glob before the build, so a clean workspace reported "Unable to access jarfile".

---

## [1.0.3] — 2026-06-30

### Added
- GitHub Actions workflow: version is now derived dynamically from the git tag and Maven POM
  (`mvn help:evaluate`) — no more hardcoded version string in the jpackage command.

### Fixed
- Second security audit — four additional issues resolved:
  - `StrictHostKeyChecking` changed from `"no"` to `"ask"`; unknown host fingerprints now show
    a confirmation dialog instead of being accepted silently.
  - Path-traversal check added to `TerminalTab.openLogFile`: the configured log directory is
    validated to be under `user.home` before any file is created.
  - `SecureFiles` utility: all application files and directories now created with owner-only
    permissions (`rw-------` / `rwx------` on POSIX; Windows equivalent via `setReadable/Writable`).
  - `CredentialStore.deriveKey`: `PBEKeySpec.clearPassword()` is now called in a `finally` block
    so the password char array is zeroed even when key derivation throws.
- Version numbers in `BuildInfo.java` and `pom.xml` were out of sync with the GitHub release tag;
  all three are now kept aligned.
- `run.bat`: JAR filename detected by glob (`14bis-ssh-*.jar`) instead of a hardcoded version
  string — the script no longer breaks after a version bump.

---

## [1.0.2] — 2026-06-28

### Fixed
- First security audit — three critical and one high-severity issue resolved:
  - **Critical — password stored as `String`**: `CredentialEntry.password` changed from `String`
    to `char[]`; passwords are zeroed with `Arrays.fill` as soon as they are no longer needed.
    `SWT Text.getTextChars()` is used everywhere to avoid creating temporary `String` objects.
  - **Critical — SSH password passed as `String`**: `SshConnection.connect` now accepts
    `char[]`; the password is encoded to a UTF-8 `byte[]` via `CharBuffer`, passed to JSch,
    and the byte array is zeroed immediately after use.
  - **Critical — vault serialization created `String` from password**: `CredentialStore` now
    serializes passwords character-by-character with `escChars(char[], StringBuilder)`, avoiding
    any `String` representation.
  - **High — application files world-readable**: log, session, vault, and settings files are now
    created with `SecureFiles` (owner-only permissions; atomic write via temp-file + `ATOMIC_MOVE`).

---

## [1.0.1] — 2026-06-27

_No code changes — tag created to mark the first publicly announced release._

---

## [1.0.0] — 2026-06-27

### Added
- **App icon** in native installers: `IconExporter` renders the application icon using AWT at
  multiple sizes (16, 32, 48, 128, 256 px) during the Maven `prepare-package` phase.
  GitHub Actions converts the PNG to ICO (Windows) and ICNS (macOS) before calling jpackage.
- Sessions tab no longer has a close button; terminal tabs keep their individual close (×) button.
- GitHub Actions release workflow builds native installers for Windows (`.exe`), Linux (`.deb`),
  and macOS (`.dmg`) in parallel with `fail-fast: false`.
- Linux package renamed to `ssh-14bis` (jpackage DEB validator requires names that start with a
  letter, not a digit).

### Changed
- Session groups can be organised in the sessions panel.
- Terminal appearance (font, foreground colour, background colour) is configurable per session.

---

## Earlier builds (pre-release, builds 1 – 46)

| Build range | Notable additions |
|-------------|-------------------|
| 37 – 46     | Session logging (plain text, ANSI stripped); single persistent `app.log`; log toggle in tab context menu |
| 30 – 36     | Credential manager with AES-256 vault protected by master password; session groups; `PasswordField` with visibility toggle |
| 20 – 29     | Scrollback buffer; mouse selection & copy; activity indicator (blinking dot on background tabs) |
| 10 – 19     | xterm-256color emulator: colour, bold, underline, reverse; alt-buffer (mc / vim) |
| 1 – 9       | Initial SSH terminal with tabbed interface; session manager; private-key authentication |
