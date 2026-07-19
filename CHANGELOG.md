# Changelog

All notable changes to Capoeira SSH are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.4.9] — 2026-07-16

### Changed
- The "Update Available" dialog now renders the release notes as proper HTML (headers,
  **bold**, tables, links, lists) instead of raw markdown text, via a new self-contained,
  escape-first markdown-to-HTML converter and an embedded browser widget (with a plain-text
  fallback if no browser engine is available on the platform).

## [1.4.8] — 2026-07-16

### Changed
- **Update checking is now manual.** The app no longer checks for a new release automatically
  on startup — the About dialog gained a "Check for Updates" button instead, which always
  reports a result (update available with release notes, already up to date, or couldn't
  check). Removed the update badge/tooltip/menu item from the Import/Export sidebar icon.

## [1.4.7] — 2026-07-16

### Fixed
- **Update check** could crash with a `StackOverflowError` (`update-check` thread) while parsing
  a release's notes: the body-field regex used a repeated capturing group, which Java's regex
  engine matches recursively — one stack frame per character — guaranteed to overflow on any
  release with normal-length notes. Replaced with a manual character scanner instead of regex.

## [1.4.6] — 2026-07-15

### Fixed
- **Terminal text selection** stayed pinned to the same screen rows instead of following the
  selected content when scrolling the scrollback after making a selection; copying could grab
  the wrong text. Selection is now tracked by absolute buffer position instead of viewport
  position.
- Dragging a selection to the top (or bottom) edge of the terminal now auto-scrolls, so text
  that had already scrolled off-screen can be selected and copied.
- **Tab traversal** in password fields (Master Password, Connect, Credential Manager, Export
  backup, etc.) stopped on the hold-to-reveal "eye" button instead of moving to the dialog's
  next field.
- **Card view** tile labels now show the session's display name (trimmed the same FQDN-aware
  way as before) instead of its host — a "user@host"-style name no longer renders as an
  awkward "vicente@Tes…" truncation.

## [1.4.5] — 2026-07-12

### Added
- **Update-available dialog**: the Import/Export sidebar icon now shows a gold badge when a
  newer release is detected. Its context menu gains a "New version available..." item that opens
  a dialog with the release notes, a button to open the release page in a browser, and an option
  to permanently dismiss update alerts.

### Changed
- The update-available indicator moved from the About icon to the Import/Export icon.

### Fixed
- The update-available badge (when it briefly lived on the About icon) was invisible: the emoji
  label filling that icon's whole area painted over the badge dot drawn on its parent.

## [1.4.4] — 2026-07-12

### Added
- **Session icons**: pick one of 36 bundled icons for a session (Session dialog → "Icon:"
  button), shown next to the session wherever it's listed.
- **Card view** for "All sessions": toggle between the flat list and a Windows-Start-Menu-style
  card view — one rounded box per group holding a compact icon-only grid, with the group name as
  a caption below. Every box renders as a uniform N×N square grid, sized from the largest group,
  regardless of how many sessions the other groups hold. Each icon shows the session's IP (or the
  first label of its FQDN) underneath so same-icon sessions in one group stay distinguishable
  without hovering. Double-clicking a group's caption renames it.
- **Drag-and-drop between groups** in Card view, and **drag-to-reorder** within List view — both
  update the session's saved `sortOrder`/`group` and re-save its file.
- The List/Cards view choice now persists across app restarts
  (`~/.capoeira/ui-state.properties`).
- **Group Manager**: create, rename, and multi-select-delete groups from the Home tab (double-click
  the "GROUPS" stat card). Deleting a group moves its sessions to Ungrouped first — it never
  deletes them.
- **Session tags**: tag a session with up to 6 labels (Session dialog → multi-select list),
  independent of its group. The "ONLINE" stat card is replaced with **TAGS**; double-clicking it
  opens a **Tag Manager** to create tags and assign each one a color, or rename/delete them across
  every session that carries them. Tags show as colored badges in List view and are searchable
  alongside name/host/group.
- **SSH verbose diagnostics**: an opt-in, per-session (and live-togglable per open tab) option that
  prints the SSH handshake — key exchange, host key, authentication negotiation — directly into the
  terminal, similar to `ssh -vvv` (Session dialog / tab "Settings…" → "SSH diagnostics").
- `CREDITS.md`: attributes the session icons' visual style to the Lucide and Feather Icons
  open-source projects.

### Changed
- The Tags field in the session dialog is a multi-select list of existing tags, not free text —
  creating a brand-new tag now happens via the Tag Manager, avoiding typo'd near-duplicates.

### Fixed
- **Import Sessions dialog**: re-scanning (or scanning two overlapping sources) listed every
  previously found session a second time instead of skipping already-listed matches.
- `run.bat` looked for the pre-rebrand jar name (`14bis-ssh-*.jar`) instead of
  `capoeira-ssh-*.jar`, left over from the Capoeira rename.
- A card-view tile turned black instead of reverting to its normal background when deselected.
- **Fifth security audit**, run in three phases (secrets/crypto/memory; filesystem/persistence;
  concurrency/UI-flow/input-validation), each adversarially verified — 33 confirmed issues, all
  fixed. Highlights: every on-disk write (sessions, tags, groups, backups) is now atomic on Windows
  as well as POSIX, closing several windows where a crash/power-loss could destroy a file mid-write;
  the credential vault's `addOrUpdate`/`mergeCredentials` now roll back instead of leaving a failed
  save's plaintext password permanently resident in memory, and the AES master key is held in a
  wipeable `byte[]` instead of a `SecretKeySpec` (whose `destroy()` doesn't actually zero anything on
  this JDK); editing a session no longer mutates the live cached object before a save succeeds;
  exporting a backup no longer follows symlinks, and importing one is now capped against a
  decompression-bomb-style crafted bundle; a global keyboard filter could hijack Delete/Enter
  keystrokes typed inside a modal dialog (Group/Tag Manager, etc.) into deleting or connecting a
  background session; search-filtered sessions could be swept into a shift-click multi-selection or
  reached via arrow-key navigation despite being hidden; case-variant or comma-containing tag names,
  and colliding group names on a case-insensitive filesystem, are now caught instead of silently
  corrupting data or merging unexpectedly; SSH verbose-diagnostic output is now sanitized before
  reaching the terminal so a malicious server can't inject escape sequences through it.

### Removed
- Dead code left over from the 1.4.0 Home-tab redesign that was never deleted after the
  migration: `SplashScreen` (never instantiated), `SessionTreePanel` and `WelcomeTab`
  (superseded by `SessionsTab`).
- 17 Eclipse compiler warnings (dead imports/methods/fields) and several public methods/classes
  with zero callers anywhere in the project, found via a dedicated whole-codebase sweep (Eclipse
  only flags unused *private* members, so these needed a separate pass): a leftover
  callback-style `reload()` overload, an unused `TerminalAppearanceDialog` constructor,
  `UpdateChecker.RELEASES_URL`, `CredentialEntry.clearPassword()`, and `SessionIconType`'s
  per-icon hex color (icons render from PNGs, the color was never read).
- Leftover `build.properties` from the pre-`BuildInfo.java` build-number scheme, never actually
  read by anything.

---

## [1.4.3] — 2026-07-07

### Fixed
Fourth security audit plus two coverage-gap follow-up passes (builds 135–145) — 17 confirmed
issues, all fixed:

- **HIGH — Zip Slip in backup import**: `BackupBundle.fromProps` derived the on-disk session
  filename from the ZIP entry name without sanitizing backslash path separators, letting a
  malicious backup escape `~/.capoeira/sessions` on Windows. Entry names are now reduced to a
  validated basename, falling back to a fresh UUID when unsafe.
- **HIGH — immutable-`String` password leak in export/import**: the plaintext
  `credentials.dat` (every saved password) was converted through un-zeroable `String`s on both
  the export and import paths. Now carried through zeroable byte/char buffers end to end.
- **HIGH — Windows ACLs are no-ops**: `SecureFiles.restrictWindows` relied on
  `File.setReadable/Writable`, which don't affect NTFS ACLs at all. Now uses `icacls` to strip
  inherited ACEs and grant the owner exclusive access.
- **HIGH — two hostile-server terminal DoS vectors**: an unbounded `CSI` parameter list could
  grow until the JVM OOM-crashed the app, and per-cell bold-`Font` allocation let a server churn
  native font handles and freeze the (single-threaded) UI. Both are now capped/cached.
- **KDF strength**: PBKDF2 iterations raised 120k → 600k (OWASP 2023 baseline), behind a new
  self-describing v2 vault/backup format with transparent, one-time v1→v2 migration on unlock.
  A follow-up fix (found by the security-audit skill's own eval run) bounds-checks the v2
  iteration count immediately after parsing — an unchecked count near `INT_MAX` could otherwise
  force 18–35 minutes of CPU-pegging computation before a corrupted/malicious file was rejected.
- Heap hygiene: `CredentialStore.lock()` now zeroes every cached password `char[]` before
  dropping it, instead of leaving plaintext passwords to linger until GC.
- `unesc()` escape round-trip silently corrupted values containing a literal backslash+`n` (e.g.
  Windows key paths) via chained `String.replace`; rewritten as a single left-to-right pass.
- Thread-safety: vault state (`masterKey`/`entries`/`salt`) is now guarded by a single monitor
  across create/unlock/lock/CRUD/persist, fixing a `persist()` TOCTOU NPE.
- Secret-lifetime and resource leaks: the SSH password `char[]` is now wiped on every exit path,
  including exceptions; session logs are capped at 100 MB to prevent disk exhaustion from a
  hostile server; per-repaint selection/overlay `Color`/`Font` handles are cached and disposed
  instead of leaking on every frame.
- P3 hardening: pasted clipboard text is stripped of control bytes before being sent to the
  shell; the terminal response queue is capped at 256 entries; the update-check response body is
  capped at 64 KB.
- Two dialog-sizing bugs clipped the vault password dialog's buttons — once under Windows DPI
  scaling, then again on Linux/GTK with a different fix — both now use `pack()`-based sizing that
  measures real widget metrics instead of a hardcoded pixel width.
- The sessions-list keyboard filter (arrows/Delete/Enter) intercepted keystrokes meant for a
  focused, connected terminal tab. Scoped to fire only while the Home tab is active, and
  generalized into a "focus follows active tab" invariant enforced on every tab switch.

---

## [1.4.2] — 2026-07-05

### Added
- **Encrypted export/import backup**: "Export backup..." bundles all sessions (and, optionally,
  the credential vault) into one AES-256-GCM-encrypted, password-protected file; "Import from
  Capoeira backup..." restores it elsewhere, merging imported credentials into the local vault
  (conflicting labels suffixed " (imported)") and automatically remapping each session's
  credential link to the merged IDs. The former separate import/export sidebar icons are unified
  into a single ⇅ menu (Export backup / Import from PuTTY or MobaXterm / Import from Capoeira
  backup).
- **Multi-select and bulk delete** in the sessions list (Ctrl/Shift-click, Delete key, "Delete N
  sessions" in the context menu).
- **Keyboard navigation** in the sessions list: arrow keys move the selection with wrap-around,
  Enter connects.
- **Duplicate session** (context menu; later relabeled "Copy") — opens the session dialog
  pre-filled from the source, preserving a renamed tab's title if the source tab had one.
- **Vault auto-lock** after 5 minutes of inactivity, a live lock/unlock sidebar icon reflecting
  vault state, and a manual "Lock vault" button in the Credential Manager. Selecting the
  locked-vault sentinel in a session's Username combo now auto-reopens the credential dropdown
  right after a successful unlock.
- **Host/port validation and auto-filled display name** in the session dialog: invalid
  host/port disables Save; a blank display name defaults to `user@host`, upgraded to
  `user@fqdn` via a background reverse-DNS lookup.

### Changed
- SSH connections now request standard PTY terminal modes (RFC 4254: `ICRNL`, `ECHO`, `ISIG`,
  `ICANON`, `OPOST`, `ONLCR` and control characters) on connect.

### Fixed
- The sessions-list right-click context menu only opened when clicking a row's edges, not
  anywhere on the row.
- Duplicating a disconnected session didn't apply the disconnected (red) tab color.
- A horizontal scrollbar appeared after the sessions list reloaded (e.g. after a delete).

---

## [1.4.1] — 2026-07-03

### Fixed
Home-screen fixes following the 1.4.0 redesign:
- The ONLINE counter and status dot didn't update when a session connected or disconnected.
- Single-click on a session row didn't connect (previously required a double-click).
- Right-click triggered connect instead of opening the context menu.
- The row hover effect disappeared when the mouse moved over a child text control.
- White corner artifacts appeared on the avatar and status-dot canvases.

### Added
- Update-available badge on the About sidebar icon; hover effect on sidebar icons.
- Session search now also filters by group name.

---

## [1.4.0] — 2026-07-03

### Added
- **Capoeira SSH rebrand**: renamed from "14bis SSH" (Java package
  `br.com.quatorzebis.ssh` → `br.com.capoeirassh.ssh`, data directory `~/.14bis` →
  `~/.capoeira`, all user-visible strings), with a new visual identity — a "Roda" icon (two
  stick figures in ginga facing each other) and an Ouro/Terracota/Noite colour palette applied
  throughout the app.
- **Redesigned Home tab**: the old session-tree panel is replaced by a card+list dashboard — an
  icon sidebar (Sessions / Credentials / Settings / About), a header with logo, search field and
  "+ New Session" button, a stats row (session/group/online counts), up to 3 recent-session
  cards, and the full session list below with avatar, group badge, status dot, hover state, and
  live search-as-you-type filtering.

### Changed
- Default terminal text colour changed to Ouro (#E8B84B).

---

## [1.3.1] — 2026-07-02

### Fixed
- No-JRE bundle: `run.bat` launched the app with `java.exe`, leaving a console window open (blank,
  doing nothing) for the app's entire lifetime. Now launches with `javaw.exe` (falling back to
  `java.exe` only if `javaw` isn't found), so the console window closes immediately.
- Renamed the bundle to `capoeira-ssh-nojre-multiplatform-<version>.tar.gz` to make clear it targets
  every supported OS/architecture, not just one.

---

## [1.3.0] — 2026-07-02

### Added
- **No-JRE bundle** (`capoeira-ssh-nojre-<version>.tar.gz`): a single, platform-independent archive
  for users who want to run Capoeira SSH on a Java runtime they already manage (21+), instead of the
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
  existing `.exe` — unzip and run `Capoeira SSH.exe` directly.
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
- Release asset filenames now include the version (e.g. `capoeira-ssh-windows-1.0.9.exe`).
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
- Windows/macOS release assets were named `capoeira.SSH-<version>.exe`/`.dmg` (jpackage's default
  naming) instead of the documented `capoeira-ssh-windows.exe`/`capoeira-ssh-macos.dmg`. Now renamed
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
  that can be unpacked anywhere and run directly via `bin/capoeira-ssh`, with no installation or
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
  intended `sessions/<group>` directory into `~/.capoeira` itself. Now falls back to a safe name.
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
- `run.bat`: JAR filename detected by glob (`capoeira-ssh-*.jar`) instead of a hardcoded version
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
- Linux package renamed to `capoeira-ssh` (jpackage DEB validator requires names that start with a
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
