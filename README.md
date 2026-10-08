# Pretty View

### A ZAP 2.17.0 add-on that adds a **Pretty** tab to the HTTP Request and HTTP Response panels — auto-detects the payload format and renders it re-formatted, word-wrapped and syntax-highlighted.

> **Status: alpha.**

---

## What it does

The add-on hooks the `RequestAll` / `ResponseAll` HTTP panel components and registers a `Pretty` view
alongside `Source` / `Header` / `Params`. When a message is selected it:

1. **Splits** headers from body (`MessageSplitter`), so the header block is preserved verbatim.
2. **Detects** the payload format (`ContentTypeSniffer`) — content type first, then body heuristics.
3. **Prettifies** the body with the matching `PrettyPrettifier`, off the EDT on a bounded worker pool.
4. **Renders** the result in an `RSyntaxTextArea` (`CustomPrettyView`) with a per-format syntax scheme
   and a notice bar that reports format detection, fallback and size decisions.

Supported formats: JSON, HTML, XML, CSS, JavaScript, GraphQL, form-urlencoded, multipart, SQL, CSV,
plain text.

### Behaviour worth knowing

- **The view opens at the top.** A large response is streamed in as chunks appended at the end of the
  document, and Swing scrolls to the caret — so the viewport used to be dragged down with every chunk
  until it sat at the bottom of the prettified response. `ChunkedTextLoader` now freezes the caret's
  update policy for the duration of a load and calls back after every chunk; `PrettyViewPanel.pinToTop`
  returns the caret to offset 0 and the scrollbar to 0 each time. Only a genuine gesture — dragging the
  scrollbar, the mouse wheel, a click in the body, or a keystroke — counts as the user taking over, after
  which the view is left alone. Watching the caret position or the scrollbar value instead would not work:
  the loader's own appends move both, so the pin would disable itself on the first chunk.
  Asserted by `/tmp/opencode/verify/Verify9.java` and `/tmp/opencode/verify/Verify10.java`, the latter
  driving the real loader rather than a stand-in.
- **Large payload policy** (`LargePayloadPolicy`) — bodies over `0x500000` chars (5 MB) are rendered
  verbatim instead of blocking on a long format pass; and a line past `MAX_WORD_WRAP_LINE_CHARS` is wrapped
  at any character rather than on word boundaries, so a minified single-line payload or a React/Next.js
  hydration blob cannot make the editor hunt for word breaks that do not exist. Line limits are asked of
  the split text the editor actually receives, so in practice over-long payloads wrap on word boundaries and
  keep their highlighting.
- **Anti-aliasing is always on** (`LargePayloadPolicy.applyForSize`). It used to be disabled past 512 KB,
  which meant a payload's size changed how its glyphs were drawn, so the same font read as a different one
  over long responses — rendering the same characters with only that flag set differs pixel for pixel. The
  cutoff bought nothing measurable and cost that, so it is gone: `applyForSize` now enables anti-aliasing
  unconditionally and `ANTI_ALIAS_LIMIT_CHARS` is removed. Both overloads set it explicitly rather than
  relying on the editor default, so an off flag left on a reused component cannot survive a load.
- **Lines are never left unwrapped** (`LargePayloadPolicy.shouldWrap`). An unwrapped line is not just a
  line you scroll sideways: long enough, the row becomes far wider than the component, and the editor then
  fails to paint all of it. On a 138 878-char JSON response whose prettified form holds one 54 332-char
  line, leaving it unwrapped asked the editor for a preferred width of 543 311 px and that row painted
  12 581 px of ink against 27 517 px for the same characters wrapped — most of the visible line was simply
  absent, which is what reads on screen as garbled text. Wrapping is what keeps the preferred width at the
  viewport's (611 px here) and paints every character.
- **Wrapping is never touched while a body streams in** (`ChunkedTextLoader.load`). It used to be switched
  off for the duration and put back when the load finished, to save re-laying out the line every chunk
  lands on; that trade has been dropped because the editor no longer sees long logical lines. The text is
  handed over already broken into display lines no longer than `DisplayLineSplitter.MAX_CHUNK_CHARS`, so a
  landing chunk only re-wraps the short line it ends in and the saving is no longer there to be had.
  Turning wrapping back on at the end, meanwhile, re-flowed the whole document in one visible jump exactly
  as loading finished — the payload looked one way while it arrived and another once it stopped. Leaving
  wrapping alone means the body is drawn the same from the first chunk to the last.
- **The editor follows ZAP's light/dark look and feel** (`EditorTheme.palette`, `PrettySyntaxScheme`). The
  panel used to be hardwired to a dark palette, so in a light ZAP the editor was a dark rectangle inside a
  white window, and its token colours were chosen against the wrong background. The palette is now read
  from `DisplayUtils.isDarkLookAndFeel()` each time the panel is themed, and the token colours move with
  it: bright cyan/tan/violet on dark, the deeper blue/maroon/green of a printed page on light, with the
  plain-text colour taken from the palette so it stays legible either way. It is re-read on `updateUI`, so
  switching ZAP's theme restyles the open editor without rebuilding the panel.
- **The wheel and the arrow keys move the view by a line** (`PrettyViewPanel.installScrolling`). Swing's
  default unit increment for a scroll pane comes from the view and can be a handful of pixels on a text
  area, which reads as a wheel that barely moves the text. The scroll bars are given a unit increment of
  one row — taken from the editor's own line height, so it follows a font change and the extra row spacing
  below — a block increment of a viewport less a row, and FlatLaf's `"JScrollPane.smoothScrolling"` client
  property so a notch on a trackpad glides instead of jumping. The block increment is recomputed when the
  viewport is resized.
- **Lines are given more room** (`PrettyTextArea`). RSTA fixes row spacing at the font's line height, which
  packs a wall of JSON or HTML tightly enough to read as a solid block. The editor subclass raises
  `getLineHeight()` by 35% of the font's own line height (at least two pixels). The syntax view, the token
  painter, the line-number gutter and the fold/overview markers all ask the text area for that value, so
  raising it here spaces the body and the gutter together — writing RSTA's private `lineHeight` field
  instead would leave the gutter misaligned with the text. The margin is a proportion rather than a fixed
  number of pixels, so it follows the font chosen under ZAP's Configure Fonts.
- **A malformed token no longer reads as a quoted one** (`PrettySyntaxScheme`, `SyntaxStyleMapper`). The
  tokenizer's two error types, `ERROR_IDENTIFIER` and `ERROR_NUMBER_FORMAT`, used to be painted in the
  string colour because they fell through to the same bucket, which made a value the language could not
  place look like a correctly quoted string; they now have a red of their own, while the *unterminated*
  string tokens stay in the string colour because for a string continued onto the next display line that
  is exactly what they are. Multipart bodies also stop being plain text: their header block is highlighted
  as a properties file, which is the closest match the tokenizer set has for `Name: value` lines.
- **The message can be searched in place** (`PrettySearchBar`, `PrettyViewPanel` context menu). Ctrl+F
  opens a find bar above the editor — pre-filled from the selection — with match-case, whole-word and
  regular-expression toggles, a live match count, and prev/next buttons; Enter and Shift+Enter walk the
  matches and Escape or the close button puts the bar away and clears its highlights. The same search is
  on the editor's right-click menu, alongside Undo / Redo / Cut / Copy / Paste / Delete / Select All, which
  the menu takes from the editor's own action map and rebuilds each time it opens, so its Copy is the
  split-aware copy the Pretty view installs rather than RSTA's default. ZAP's request and response views
  put their search where the message is, so this is the same idea brought into the Pretty view, and it is
  backed by RSTA's own `SearchEngine` rather than a second implementation.
- **ZAP's own request and response panels get the same right-click menu** (`TextContextMenu`,
  `ExtensionPrettyView.installCoreContextMenus`). The core text views otherwise carry only ZAP's message
  menu (Open, Resend and so on), with no Cut, Copy, Paste or Find, so `postInit` walks the live request
  and response panels, finds every `HttpPanelTextArea`, and puts the shared menu in place of whatever
  the view had, remembering it so unloading the add-on restores it. Because the menu is built from the
  target's action map at open time it works unchanged on both the RSTA editor and the plain text areas,
  greys out Undo / Redo / Cut / Paste / Delete on the read-only core views, and sends Find to ZAP's
  Search tab (`SearchPanel.searchFocus`) rather than the Pretty view's inline bar.
- **Over-long lines are broken at 2 000-character boundaries, held back to 4 000 while a string or a
  comment is open** (`DisplayLineSplitter.copyChunked`). Wrapping alone is not enough to keep scrolling
  smooth, because the editor wraps by handing the whole logical line to a layout view and redoing that
  work on every repaint. One 54 331-char line in the 118 871-char prettified response above cost
  **17 ms median and 70 ms p95 per repaint** at 18 pt, which is the stutter felt while scrolling up and
  down. The identical characters handed to the editor as 2 000-char lines repaint in **6 ms median and
  21 ms p95**. The seam between pieces shows in the editor but not in the payload: every copy hands back
  the characters as the response holds them, and an edited body is written back to the message in its
  original form rather than with the seams in it. A break is held off while a string literal or a comment
  is open, so a long `"aaaa…"` reaches the editor whole; a string longer than 4 000 is cut as late as it
  can be, and the half beyond the seam is still drawn as a string. That last part is `ContinuedStringTokenMaker`:
  a token maker that sees the previous line end with an open quote records the quote and the delegate's own
  continuation state in the value it returns, and reads the next line from there — up to the closing quote
  as one string token, the rest handed back to the delegate as the language it belongs to. Without it the
  editor tokenizes the continuation from scratch and it reads as bare text. Chunk size was
  measured, not guessed: 1 000 chars gave 7 ms/20 ms, 2 000 gave 5-6 ms/14-21 ms, 4 000 gave 8 ms/19 ms.
- **The font is ZAP's, not a hardcoded one** (`EditorTheme.configuredFont`). The editor, its gutter and its
  overview ruler all use the font ZAP is configured to use for work panels — the one set under *Configure
  Fonts* — falling back to ZAP's own default font when the user has not set one. This is the same lookup
  ZAP makes for its own syntax highlighted text area, including the font-family fallback for a family that
  is not installed on the machine. It is re-read on `updateUI`, so a font changed in ZAP is picked up
  without rebuilding the panel. Outside a running ZAP, ZAP's font map is never populated and every lookup
  throws, so the code falls back to a monospaced font at 18pt rather than letting the panel fail to open.
  `LargePayloadPolicy.applyBaseline` also enables fractional font metrics here: a monospaced glyph is not a
  whole number of pixels wide, and RSTA only turns fractional measurement on by itself on some platforms,
  so asking for it makes the same text space the same everywhere.
- **Syntax highlighting survives an over-long line** (`LargePayloadPolicy.syntaxStyleFor`). Highlighting
  used to be dropped for a payload with a pathologically long line, because the editor re-tokenizes the
  whole logical line that a painted row belongs to, so repaint cost is set by the *longest line* rather
  than by the part of it on screen: a 118 871-char prettified JSON response holding one 54 332-char line
  repainted in 132 ms median against a 16 ms frame. That was measured before lines were being split for
  display, and it no longer applies, because the editor is now handed pieces of at most 4 000 characters
  and never holds the 54 332-char line at all. The style is therefore decided from the text the editor
  actually receives (`split.displayText()`) rather than from the formatted text: judging the formatted
  text would read a long line off a document that no longer contains one and strip the colours off a
  payload that is entirely highlightable. Restoring it costs nothing measurable — 3-5 ms median and
  7-9 ms p95 per repaint over 60 scroll positions with `text/json` on, against 10 ms/23 ms for the same
  text unhighlighted on the same machine, which is inside the noise. The 132 ms figure above is why the
  limit still exists for text that reaches the editor unsplit. Anti-aliasing was never the culprit
  either: turning it off moved the 132 ms median only to 122 ms, which is why it is now simply always on.
- **Size limits are asked of the payload, line limits of the displayed text** (`LargePayloadPolicy`). The
  two answer different questions, so they read different texts. A character limit — unhighlighted text past
  `SYNTAX_OFF_LIMIT_CHARS` (5 MiB) — asks how big the payload is, so it is asked of the formatted text. The
  line limits — wrapping style and `MAX_SYNTAX_LINE_CHARS` — ask what the editor has to lay out and tokenize,
  so they are asked of the split text, which is the only text the editor holds. Asking a size limit of the
  split text would credit the seams to the payload: on the response above the split text is 118 927
  characters against a formatted 118 871, which is enough to flip a payload that sat within 56 characters of
  a limit. This distinction only matters because the seams exist; anti-aliasing, which had a character limit
  of its own until it was removed, was judged on the formatted text throughout.
  (`PrettyDefaultViewSelector`). ZAP sorts its default view selectors by order ascending and opens the first
  one that matches, so the position matters: ZAP ships an image selector at 20 and large request and
  response selectors at 50, and this one sits at 30. That is above the image selector, so an image response
  still opens in the image view instead of being prettified as if it were text, and below the large body
  selectors, so Pretty wins for an ordinary payload no matter how big. Asserted by
  `/tmp/opencode/verify/Selector.java`.
- **A body past the prettify threshold switches to the text view** (`PrettyDefaultViewSelector`). Past
  `PrettifyThreshold`'s 5 MB the add-on stops prettifying and hands the body back verbatim, so there is
  nothing left for it to offer and the selector declines the message instead; ZAP's own large body selector
  then matches and the panel opens in its plain text view. The selector and the prettifier are held to the
  same boundary, so Pretty is the default exactly when it has something to prettify — a body of exactly
  5 MB still prettifies and still opens in Pretty, and one byte more does not.
- **Chunked loading** (`ChunkedTextLoader`) keeps the notice bar responsive while the document streams in.
- **Write-back** (`PrettyWriteBack`) pushes edits made in the Pretty tab back to the underlying message.
  Editing matches the core panels: the flag the host panel passes is honoured rather than forced, so the
  main-window request and response tabs are read-only and only the Manual Request Editor's request accepts
  edits and writes back. Responses are never written back.
- **Fail-open everywhere** — every prettifier failure is caught and the raw body is shown instead;
  detection and formatting never block the panel.
- **Four-space indentation** — every prettifier emits four spaces per level, and never a tab. The unit
  is `PrettyPrettifier.INDENT` (`"    "`), with `PrettyPrettifier.INDENT_WIDTH` for the backends that
  want a column count instead of a string; change that one constant to restyle every format. Four is
  also what the two backends that indent on their own already use by default — jsoup's `indentAmount`
  and Xalan's `indent-amount` are both 4 — so the constant now leaves them at their own default instead
  of overriding it. JSON used to be the exception, because `Gson`'s `setPrettyPrinting` hardcodes two
  spaces and exposes no setting; `JsonPrettifier` now serialises through a `JsonWriter` carrying
  `PrettyPrettifier.INDENT`, and `Gson.toJson(JsonElement, JsonWriter)` copies its own `htmlSafe` and
  `serializeNulls` flags onto that writer without touching the indent, so JSON follows the same constant
  as everything else. jsoup has a second, quieter cap on the same indentation: `OutputSettings.maxPaddingWidth`
  defaults to **30**, so any element nested deeper than that was indented to a fixed 30 columns and the tree
  stopped reading as a tree. `HtmlPrettifier` now sets `.maxPaddingWidth(-1)`, which removes the cap. On a
  real zooplus.com homepage the lines whose indent was not a multiple of four fall from 2 114 to 89, and the
  89 that remain are continuation lines inside a multi-line `srcset` attribute, which is the attribute's own
  text and is left as written.
- **One True Brace Style (1TBS)** — the opening brace of a block stays on the line that introduces it,
  and only its body moves down a level: `if (a) {` / `    b()` / `}`. The closing brace realigns to the
  start of the statement that opened the block. This holds in JavaScript, CSS and GraphQL alike, so
  `body {`, `@media (max-width: 600px) {`, `query Q {` and `hero(id: $id) {` all read the same way.
  A brace that opens an *expression* rather than a block is part of that expression and stays
  attached to it, which is what keeps `return {x:1}`, `var o={a:1}` and `()=>({a:1})` intact. A brace
  with nothing to attach to — a bare block, or the body of a `case` label — opens its own line.
  `(` and `[` keep their trailing position. Embedded `<script>` / `<style>` code inherits the rule, so
  braces stay consistent no matter which format they arrive in. SQL, XML, HTML, JSON, CSV, form and
  multipart payloads have no braces and are unaffected.
- **Spacing inside brackets is left alone.** The prettifiers add line breaks, indentation and the
  1TBS brace, but they do not insert spaces around `=`, `=>` or `:` in code, so `var o={a:1}` and
  `episode:JEDI` stay as written. Rewriting token spacing needs a real parser and is deliberately out
  of scope.
- **Embedded HTML code is nested** — a multi-line `<script>` or `<style>` body is moved onto its own
  lines, indented one level in from its opening tag, with the closing tag realigned to match it; the
  body's own relative indentation is preserved. Single-line bodies stay inline, `<script src>` is never
  rewritten, and the whitespace inside `<pre>`, `<textarea>` and `<template>` is passed through
  byte-for-byte because jsoup prints raw-text nodes verbatim and would otherwise strand them at
  column 0.

---

## Project structure

The tree keeps one package layout:

```
Pretty/
├── ZapAddOn.xml                     add-on manifest (name, version, status, bundle, extensions)
├── build.gradle.kts                 Gradle build (org.zaproxy.add-on 0.13.1)
├── settings.gradle.kts
├── build.sh                         javac + zip build against ZAP 2.17.0
├── lib/
│   ├── jsoup-1.17.2.jar             vendored, shaded into the .zap (HTML parsing)
│   └── gson-2.11.0.jar              vendored, shaded into the .zap (JSON / GraphQL)
└── src/main/
    ├── java/org/zaproxy/zap/extension/prettyview/
    │   ├── ExtensionPrettyView.java     extension entry point, view registration + unload
    │   ├── async/                        PrettyWorker, PrettyWorkerTask, ExecutorHolder
    │   ├── detect/                       ContentTypeSniffer, MessageSplitter, PayloadFormat
    │   ├── formatters/                   PrettyPrettifier + 11 implementations + manager
    │   ├── ui/                           CustomPrettyView, Panel, Model, NoticeBar, theme, sizing
    │   └── view/                         default view selectors
    └── resources/org/zaproxy/zap/extension/prettyview/resources/
        └── Messages.properties           i18n bundle, prefix `prettyview`
```

### Package map

| Package | Responsibility |
| --- | --- |
| `prettyview` | `ExtensionPrettyView` — registers the Pretty view and default view selectors on `RequestAll` / `ResponseAll`, and removes them cleanly on unload. |
| `prettyview.detect` | `MessageSplitter` (headers vs body), `PayloadFormat` enum, `ContentTypeSniffer` (content-type then body sniffing). |
| `prettyview.formatters` | `UniversalPrettifierManager` (registry + threshold + fail-open), `PrettyResult` (text / format / fallback / note / overThreshold), and one `PrettyPrettifier` per format. |
| `prettyview.async` | `PrettyWorker` bounded executor + `PrettyWorkerTask`, `ExecutorHolder` lazy init. |
| `prettyview.ui` | `CustomPrettyView` / `PrettyViewPanel` / `PrettyViewModel`, `PrettyNoticeBar`, `ChunkedTextLoader`, `EditorTheme`, `PrettySyntaxScheme`, `SyntaxStyleMapper`, `PrettySearchBar`, `PrettyTextArea`, `TextContextMenu`, `LargePayloadPolicy`. |
| `prettyview.view` | `PrettyDefaultViewSelector` + factory — when the Pretty view should become the default. |

---

## Build

```bash
./build.sh
```

Compiles against `/opt/ZAP_2.17.0`, shades jsoup and gson into the add-on, and packages
`build/dist/prettyview-alpha-1.0.0.zap`.

Install: **ZAP GUI → Manage Add-ons → Install local add-on (.zap file)**

Gradle is also configured (`org.zaproxy.add-on` 0.13.1, Java 17, ZAP 2.17.0) if you prefer
`gradle addOn` / `gradle copyZapAddOn`.

---

## Reference

### Tests

- Edge cases for the embedded-block indentation are asserted in `/tmp/opencode/verify/Verify3.java`:
  single-line bodies stay inline, `<script src>` is untouched, `<pre>` / `<textarea>` keep their tabs
  and trailing spaces, and every closing tag lands back on its opening tag's column. Note that jsoup
  re-serialises `<template>` children (collapsing their whitespace), which is preserved rather than
  corrected.
- 1TBS is asserted by `/tmp/opencode/verify/Verify8.java` (54 checks): the brace attaches for JS
  `if` / `else` / `else if`, `for`, `while`, `do`, `switch`, `try` / `catch` / `finally`, function
  declarations, classes and methods, async arrows and blocks nested inside a `case` body; it also
  covers CSS type, pseudo-class, pseudo-element, functional, attribute and nested selectors, at-rules
  and at-rule nesting, and GraphQL operations, arguments, fields, mutations, fragments and anonymous
  operations. A companion sweep asserts that no block brace is ever left alone on its line. The
  closing-brace alignment and column invariants for all three formats are checked by
  `/tmp/opencode/verify/Verify4.java`.
- `/tmp/opencode/verify/Verify5.java` (133 checks) is the regression suite for the defects listed
  below, and `/tmp/opencode/verify/Edge.java` checks keyword lookalikes (`{case:1}`, `{default:2}`,
  `export default {…}`, `export {a,b}`, `import {a} from "m"`, `case {a:1}:`), async arrows,
  ternaries, template literals, classes and dynamic `import()` — content is preserved in every case.
- `/tmp/opencode/verify/FormatFixes.java` (38 checks) covers the two formatting defects fixed most
  recently: it asserts that no CSS output line ends in a comma, that a `url(data:…)` comes back
  character for character, and that no JavaScript line is left holding a lone `;`, `)` `]` or `,` —
  for assignments, calls, callbacks, object members and nested blocks alike.
- `/tmp/opencode/verify/SplitCheck.java` (11 227 checks) covers the display split, including the
  string rule: a payload whose two strings straddle the 2 000-character boundary comes back in one
  piece per string, no break lands inside a string short enough to keep whole, a comment's apostrophe
  does not miscount as one, and every split still hands the original text back.
- `/tmp/opencode/verify/ZooplusTokenCheck.java` runs the split of the zooplus.com homepage through
  `ContinuedStringTokenMaker` and asserts that every display line ending inside an open string is
  followed by a line whose first token is that same string. It finds 63 open-string lines and 0
  uncoloured continuations; run against the stock token maker the same payload loses all 63.

### Defects fixed

Each of these was a real defect in the formatter's output; they are all corrected:

- **`switch` labels were mangled.** The formatter broke the line straight after a keyword, so
  `switch (a) { case 1: b(); … }` emitted `case` and `1:b();` on separate lines and split `default:`
  the same way. `case` / `default` are now recognised before the flush and their values and bodies
  stay put, including fallthrough, `case X: {…}` and blocks nested inside a case body.
- **Object literals were treated as blocks.** `return {x:1}` and `var o = {a:1}` put the `{` on its
  own line and stranded the closing brace. A brace now opens a *block* only when it really does;
  literal braces stay attached to their expression and their contents indent one level, so nested
  literals, literals in arrays and literals as call arguments all read correctly. Arrow function
  bodies after `=>` remain blocks.
- **A statement closer was stranded on a line of its own.** `var f=function(){a();};b();` put the `;`
  that closes the `var` statement on the line after the brace, and the same happened to the `});` of a
  call and the `},` of an object member, so `foo(function(){x();})` ended `x();` / `}` / `);`. The line
  break a closing brace now owes is held until text arrives that does not attach to it — `;`, `)`, `]`,
  `,` and `.` do attach — so `};`, `});`, `},` and `},1000);` stay on the brace's line, while the next
  statement still starts a line of its own.
- **Anonymous GraphQL operations threw.** A payload starting with `{` was always routed to the JSON
  envelope parser, so `{hero{id}}` failed with `PrettificationException`. The parser now tries the
  envelope first and only treats the payload as an operation when there is no `query` member, which
  keeps the envelope, batched-array and plain-JSON paths working.
- **Loop-header detection was inert.** `lastWord` was cleared inside `handleCode` before the main loop
  tested it against `LOOP_KEYWORDS`, so `loopParenBase` was never set and `for (;;)` / `while (…)`
  headers were split after every `;`. The keyword is now captured first and the paren depth is
  released when the header closes, so later statements still end their lines.
- **CSS pseudo-selectors were corrupted.** Every colon got a trailing space, so `b:hover` came out as
  `b: hover` and `b::before` as `b:: before` — both invalid CSS. A colon now takes a space only where
  CSS wants one: in a declaration (detected by brace depth plus the property name being a bare
  identifier) and in an at-rule or function argument such as `@media (max-width:600px)`. In a
  selector nothing is added, so `:root`, `a:not(.x)`, `input[type="text"]:focus` and nested `&:hover`
  all stay valid.
- **A CSS comma ended the line it appeared on.** The formatter broke the line after every comma, so
  `a,b{color:red}` came back as `a,` / `b {`, `font-family:Arial,sans-serif` as `Arial,` / `sans-serif`
  and a `box-shadow` with two shadows spread over three lines. A comma that closes nothing now only
  separates the values it was written between: selectors, declaration values, `url()` lists, at-rule
  conditions and `@import` lists all stay on one line, while a comma still ends a line where the next
  thing is a closing brace (`},` for a member).
- **A `url(data:…)` was reformatted as though it were CSS.** `url(data:image/png;base64,AAA)` came out
  as `url(data: image/png;` / `base64,` / `AAA)` — a space after every colon, a break after every `;`
  and after the comma inside the base64 run — which is not a URL a browser will load. Everything inside
  `url(…)` is now text and is passed through as it was written, including for the upper-case `URL(`
  spelling, while a colon in an at-rule argument such as `@media (max-width:600px)` still takes its
  space.
- **Brace style was Allman, not 1TBS.** The opening brace of a block used to go on its own
  line, so `if (a) {b()}` rendered as `if(a)` / `{` / `b()`, and `a.map(function(x){…})` split the
  brace away from the parameter list it belongs to. Every block brace is now attached to the line
  that introduces it, and the closing brace realigns to the start of that statement. A brace with
  nothing to attach to — a bare block, or a `case` body — still opens its own line. This was applied
  to JavaScript, CSS and GraphQL together so the claim is true of all three.
  See [1TBS](#behaviour-worth-knowing).
- **The view scrolled to the bottom of large responses.** Opening a request or response with a large
  content length left the viewport scrolled to the end of the prettified body instead of the start. A
  large payload is loaded in chunks appended at the end of the document, and `DefaultCaret` follows
  insertions at its own position, so the caret — and therefore the viewport — tracked the growing end of
  the document and finished at the bottom. See [The view opens at the top](#behaviour-worth-knowing).
- **JavaScript was routinely misdetected.** With no `Content-Type`, or a generic one such as
  `application/octet-stream`, `function f(a,b){return {x:a}}`, `if(a){b()}else{c()}` and
  `for(var i=0;i<9;i++){f(i)}` all came back as `GRAPHQL`, and `var cfg={a:1,b:{c:2}}` came back as
  `CSS`. The cause was `GRAPHQL_START`'s shorthand alternative matching any `{` followed by a word
  and then `(` or `{` — which is precisely the body of a JavaScript block — combined with a broad
  `CSS_BLOCK`. Detection now recognises JavaScript from a keyword or `=>` found after comments and
  string literals are stripped, and only treats `{hero{id}}` as GraphQL once that has been ruled out.
  See [Detection order](#detection-order).
- **Vendor and custom JavaScript types were unrecognised**, so anything outside the hard-coded list
  fell through to plain text even when the body was plainly a script. Matching is now on the subtype.
  See [JavaScript content types](#javascript-content-types).

The 1TBS change also fixed one genuine defect as a side effect: `CssPrettifier` used to flush the
selector line *after* incrementing the indent, so every nesting level drifted one step too deep and
`@media` blocks came out four levels in.

### JavaScript content types

`PayloadFormat.JAVASCRIPT` lists the types seen in the wild — `application/javascript`,
`text/javascript`, `application/x-javascript`, `text/x-javascript`, `application/ecmascript`,
`text/ecmascript`, `application/x-ecmascript` and `module`. Beyond that list,
`ContentTypeSniffer.isJavaScriptMediaType` matches on the **subtype**, so vendor and custom types work
without being added to the enum:

| matches | does not match |
|---|---|
| `application/javascript1.5`, `application/x-js`, `text/js` | `application/json`, `application/xhtml+xml` |
| `application/vnd.acme.javascript`, `application/vnd.zoo.js` | `text/css`, `application/xml` |
| `application/ld+javascript`, `application/x-my-custom-javascript` | `application/octet-stream` |
| `text/ecmascript` and any `*ecmascript*` | |

Matching is on the substring `javascript` / `ecmascript`, or a trailing `js` token separated by
`-`, `.` or `+`. The media type is lowercased and stripped of `;charset=…` first, so
`APPLICATION/X-JAVASCRIPT; charset=utf-8` resolves correctly.

### Detection order

`ContentTypeSniffer.fromBody` runs in this order, and each step exists because the one before it
would otherwise claim the body:

1. **HTML** — an allowlist of tags, so it cannot be confused with a script.
2. **GraphQL with an explicit keyword** — `query` / `mutation` / `subscription` / `fragment`, with an
   optional name so `mutation { … }` counts. Checked first because a field can legitimately be named
   `new`, `for` or `in`, which the JavaScript signature would otherwise key on.
3. **JavaScript** — a keyword or `=>` found *after* comments and string literals are removed, so
   `a::before{content:"new"}` and `/* use var here */a{color:red}` are not read as scripts. Ahead of
   the GraphQL shorthand because `{b()}` — the body of a JavaScript block — looks exactly like a
   selection set.
4. **GraphQL shorthand** — `{hero{id}}`, anchored to the start of the body.
5. **JSON**, **XML**, **JSONP**, **SQL**, **form-urlencoded**, **CSS**, **Markdown**, plain text.

`in` and `of` were dropped from the keyword list and a preceding `-` is excluded, because CSS values
such as `ease-in-out` and `ease-in` otherwise matched.

### Binary payloads

A body of bytes is shown verbatim, never passed to a text formatter. Two checks in
`ContentTypeSniffer.detect` run before both the header and the body heuristics:

1. **An unambiguous binary content type wins.** `application/x-protobuf`, `application/protobuf`,
   `application/proto`, `application/x-google-protobuf`, `application/google-protobuf`, the
   `application/grpc*` family, `application/pdf`, the archive and compression types,
   `application/wasm`, plus every `image/*`, `audio/*`, `video/*` and `font/*` top-level type
   resolve to plain text.
2. **Bytes that cannot occur in text.** `looksBinary` scans the same 8192-character window every
   other signal uses and reports a NUL or any C0 control byte other than tab, newline and carriage
   return, which is what protobuf length prefixes such as `0x08`, `0x12` and `0x18` are made of.

`application/octet-stream` is deliberately **not** in that list. It is a generic catch-all that
servers also use for scripts, so it stays body-sniffable and a script served as
`application/octet-stream` is still detected as JavaScript.

This was a real corruption bug. A Google RPC request to
`$rpc/google.internal.onegoogle.asyncdata.v1.AsyncDataService/GetAsyncData` is served as
`application/x-protobuf`; that type matched no formatter, so the body fell through to sniffing,
matched the form-urlencoded heuristic (`=`, `&`, no spaces) and was then **URL-decoded**:
`a%20b` became `a b`, `+` became a space, `e%26f` became `e&f`, and `raw:` lines were interleaved —
126 bytes of input rendered as 179 bytes of different text. Binary payloads are now detected and
passed through unchanged.

### Known limitations

- **Minified input is handled** for JS, CSS and HTML: the formatters are character-driven rather than
  line-driven, so a single-line payload such as
  `for(var i=0;i<10;i++){if(i%2){f()}else{g()}}` or `body{margin:0}a{color:red}` expands correctly.
- **Sniffing without a `Content-Type` header is a heuristic**, and a declaration normally wins over it,
  so a mis-declared or generic type (`application/octet-stream`, `text/plain`) still resolves
  correctly from the body. The exception is an unambiguous binary declaration, which is honoured so
  that binary is never read as text; see [Binary payloads](#binary-payloads). A bare object literal
  with no JavaScript keyword, such as `{a:1}`, has no signal to key on and falls back to JSON.
- **HTML is re-serialised by jsoup**, which lowercases `<!DOCTYPE html>` to `<!doctype html>`, inserts
  an empty `<head></head>`, and collapses whitespace inside `<template>`. Nothing is lost, and it is
  left as jsoup produces it rather than corrected.
- **A bare token under `application/json` gains quotes.** `c8b03804-0909-…` is not valid JSON, but
  gson's lenient reader accepts it as a string and prints `"c8b03804-0909-…"`, so two characters are
  added. This is the one place a payload can gain characters rather than only whitespace.
