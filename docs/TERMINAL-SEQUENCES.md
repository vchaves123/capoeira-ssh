# Terminal sequences — what Capoeira SSH handles

Reference for the built-in terminal emulator (`TerminalEmulator`). Capoeira declares itself as
`TERM=xterm-256color`, so this lists what xterm defines and, for each item, whether Capoeira
handles it.

Status: ✅ handled · ⚠️ partial (see note) · ➖ consumed and ignored on purpose · ❌ not implemented

Every sequence that is received but not acted on (❌, ➖ and the ignored part of ⚠️) is written
once per tab to `~/.capoeira/log/app.log` as `unhandled: …` — see [Diagnosing a gap](#diagnosing-a-gap).

> The `xterm-256color` capability list used by `TerminfoConformanceTest` was transcribed by hand
> (no `infocmp` on the Windows build machine). Re-check it against `infocmp -1 xterm-256color` on
> a Linux box if the entry may have changed.

## C0 controls

| Control | Status | Note |
|---|---|---|
| BEL | ➖ | no audible/visual bell |
| BS, HT, LF/VT/FF, CR | ✅ | |
| SO / SI (G1 / G0) | ✅ | |
| CAN / SUB | ✅ | cancel the sequence in progress |
| ESC | ✅ | |
| DEL (0x7F) | ✅ | ignored everywhere, as xterm |
| NUL, ENQ, others | ➖ | |

## C1 controls (8-bit)

| Control | Status | Note |
|---|---|---|
| IND, NEL, RI | ✅ | |
| CSI, OSC, DCS, SOS, PM, APC, ST | ✅ | strings are consumed; see OSC/DCS below |
| HTS, SS2, SS3, DECID, others | ❌ | logged |

## ESC sequences

| Sequence | Status | Note |
|---|---|---|
| `ESC 7` / `ESC 8` (DECSC/DECRC) | ✅ | position, SGR, charsets, origin mode |
| `ESC D` / `E` / `M` (IND/NEL/RI) | ✅ | |
| `ESC H` (HTS) | ✅ | |
| `ESC c` (RIS) | ✅ | |
| `ESC ( 0`, `ESC ( B`, `ESC ) 0`, `ESC ) B` | ✅ | DEC Special Graphics / ASCII |
| other G0/G1 sets, multi-byte designators | ⚠️ | treated as ASCII, logged |
| `ESC * x`, `ESC + x`, `ESC - x`, `ESC . x`, `ESC / x` (G2/G3) | ➖ | consumed, logged |
| `ESC # 8` (DECALN) | ✅ | |
| `ESC # 3/4/5/6` (double-size lines) | ➖ | consumed, logged |
| `ESC % G` (UTF-8) | ✅ | UTF-8 is the only encoding |
| `ESC % @` | ➖ | logged |
| `ESC SP F` (S7C1T) | ✅ | replies are 7-bit |
| `ESC SP G` (S8C1T) | ➖ | logged |
| `ESC =` / `ESC >` (DECKPAM/DECKPNM) | ❌ | application keypad not implemented |
| `ESC l` / `ESC m` (HP memory lock) | ❌ | logged |
| `ESC \` outside a string | ➖ | silent |

## CSI sequences

| Sequence | Status | Note |
|---|---|---|
| CUU/CUD/CUF/CUB `A B C D`, CNL/CPL `E F`, CHA `G`, CUP/HVP `H f`, VPA `d` | ✅ | |
| HPA `` ` ``, HPR `a`, VPR `e` | ❌ | xterm-256color uses `G`/`d` instead |
| ED `J` (0–3), EL `K` (0–2) | ✅ | `J 3` clears the scrollback |
| IL `L`, DL `M`, ICH `@`, DCH `P`, ECH `X` | ✅ | |
| SU `S`, SD `T` | ✅ | |
| REP `b` | ✅ | capped at one screenful |
| CHT `I`, CBT `Z` | ✅ | |
| TBC `g` | ✅ | |
| DECSTBM `r` | ✅ | |
| SCOSC `s`, SCORC `u` | ✅ | |
| SGR `m` | ✅ | see SGR |
| SM/RM `h`/`l` — IRM (4) | ✅ | |
| SM/RM — LNM (20), others | ❌ | logged |
| DECSET/DECRST `? … h/l` | ✅ | all modes in the list applied; see DEC modes |
| DSR `5 n`, `6 n`; DECXCPR `? 6 n` | ✅ | |
| DA `c`, DA2 `> c` | ✅ | DA: `?1;2c`; DA2: `>1;10;0c` (VT220) |
| XTVERSION `> q` | ✅ | `DCS >\|Capoeira SSH <version> ST` |
| DECRQM `? Ps $ p`, `Ps $ p` | ✅ | 0 for unimplemented modes |
| XTWINOPS `t` — 14, 16, 18, 19 (reports), 22/23 (title stack) | ✅ | |
| XTWINOPS — move/resize/iconify… | ➖ | a remote program must not move or resize the window |
| DECSCUSR `Ps SP q` (cursor style) | ❌ | the cursor is always a block |
| DECSTR `! p` (soft reset) | ❌ | logged |
| DECSED/DECSEL `? J`, `? K` | ❌ | logged |
| DECSLRM / left-right margins (`? 69`) | ❌ | logged |
| Kitty keyboard `? u`, `> u`… | ❌ | no reply is that protocol's "unsupported" answer |
| xterm modifyOtherKeys `> 4 ; 2 m` | ❌ | logged |
| Colon sub-parameters (`4:3`, `38:2::R:G:B`) | ✅ | in SGR; any other CSI with `:` is dropped whole |

## DEC private modes (`CSI ? Ps h/l`)

| Mode | Status | Note |
|---|---|---|
| 1 DECCKM (application cursor keys) | ✅ | |
| 3 DECCOLM (132/80 columns) | ✅ | only when the session allows it |
| 4 smooth scroll, 12 cursor blink | ➖ | cosmetic, silent |
| 5 DECSCNM (reverse screen) | ❌ | so `flash` (visual bell) does nothing |
| 6 DECOM, 7 DECAWM, 25 DECTCEM | ✅ | |
| 47, 1047, 1049 (alternate screen) | ✅ | 1049 saves/restores the cursor, also on a second exit |
| 9, 1000, 1002, 1003 (mouse) | ✅ | Shift+mouse selects locally |
| 1005, 1006, 1015 (mouse encodings) | ✅ | legacy encoding drops coordinates past 223 |
| 1004 (focus events) | ✅ | |
| 1007 (alternate scroll) | ✅ | |
| 2004 (bracketed paste) | ✅ | |
| 1034 (meta key) | ❌ | logged |
| 2026 (synchronized output) | ❌ | DECRQM answers 0 |

## SGR (`CSI … m`)

| Parameter | Status | Note |
|---|---|---|
| 0, 1, 4, 5, 7, 22, 24, 25, 27 | ✅ | 22 ends bold and faint |
| 2 faint, 3 italic, 8 concealed, 9 strike-through; 23, 28, 29 | ✅ | |
| 4:0 … 4:5 underline styles | ⚠️ | every style drawn as a single underline |
| 30–37, 39, 40–47, 49, 90–97, 100–107 | ✅ | |
| 38/48 `;5;n`, `;2;R;G;B`, `:5:n`, `:2::R:G:B`, `:2:R:G:B` | ✅ | |
| 58 underline colour | ➖ | arguments consumed, colour not drawn |
| 21 double underline, 53 overline, 10–19 fonts, others | ❌ | logged |

## OSC (`ESC ] Ps ; Pt ST`)

| Ps | Status | Note |
|---|---|---|
| 0, 2 (window title) | ✅ | |
| 1 (icon name) | ❌ | logged |
| 4 `;n;?` (palette query) | ✅ | |
| 4 (set palette), 104 (reset) | ❌ | the palette is fixed |
| 10, 11, 12 `;?` (colour queries) | ✅ | reply mirrors the BEL/ST terminator |
| 10/11/12 set, 110–112 | ❌ | logged |
| 52 (clipboard) | ✅ | **write-only**; the `?` read is never answered; "Copied" indicator shown |
| 7 (cwd), 8 (hyperlink), 9/777 (notification), 133 (shell integration) | ❌ | consumed; hyperlink text still shows |

Payloads of ignored OSC/DCS strings are never logged — only their number and length.

## DCS / APC / PM / SOS

All consumed and logged (by kind and length): sixel and kitty graphics, DECRQSS, XTGETTCAP,
tmux passthrough are not implemented.

## Answers sent to the remote side

DA (`?1;2c`), DA2 (`>1;10;0c`), XTVERSION, DSR 5/6, DECXCPR, DECRQM, XTWINOPS 14/16/18/19,
OSC 4/10/11/12 queries. The local clipboard is never sent.

## Diagnosing a gap

1. **`app.log`**: `~/.capoeira/log/app.log` gets one line per distinct unhandled sequence per tab,
   e.g. `2026-11-23 19:30:12 [terminal "server"] unhandled: CSI ? 2026 h` (capped at 200 per tab).
2. **Byte trace**: right-click the tab → *Trace Bytes to File*. Every byte sent and received, plus
   `RESIZE` lines; `Ctrl+Shift+D` adds a full snapshot of the emulator state.
3. **Tests**: `TerminfoConformanceTest` feeds every `xterm-256color` capability to the emulator and
   fails on anything unhandled that isn't listed in its `KNOWN_GAPS` (each with a reason);
   implementing a known gap makes it fail until the entry is removed, so the list stays honest.
   When you implement something here, update this file too.
