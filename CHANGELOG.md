# Changelog

All notable changes to 14bis SSH are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
