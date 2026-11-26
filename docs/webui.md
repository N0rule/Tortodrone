# WebUI

The WebUI is the browser administration surface served under `/admin/`. It is a
plain ES-module application with no build step, no framework and no external
network requests: `frontend/` is copied to `webui/` beside the JAR by
`./gradlew buildWebui`, and the server serves those files verbatim.

- **Scope:** accounts, knights, inventory, economy, Depot, auctions, player mail,
  Uplink News, content catalogue, console, logs, WebUI access, the audit trail and
  browser-local preferences.
- **Roles:** `VIEWER` monitors status, sessions and logs; `MODERATOR` also inspects
  accounts and knights and can disconnect sessions; `ADMINISTRATOR` mutates
  accounts, economy, content and process lifecycle. Navigation only offers pages
  the role may call, and the server re-checks every request.
- **Transport:** same-origin only, HttpOnly session cookie plus a session-bound
  CSRF token on every write. See
  [Operations](operations.md#secure-webui-setup) for direct HTTP and TLS-proxy
  deployment.

## Layout and navigation

The sidebar groups pages by task — Monitor, People, Economy, Content, Operations —
so a page is recognisable by where it sits, not only by its name. The page heading
repeats the section as an eyebrow, the title as the `h1`, and one sentence
describing what the page is for.

The top bar holds the command palette trigger, the connection state, the shortcut
reference and the clock. At 900 px and below the sidebar becomes a drawer with a
scrim, Escape closes it and focus returns to the menu button.

## Command palette

`Ctrl`/`⌘` + `K`, or the search trigger, opens the palette. It is one combobox
driving a grouped listbox:

| Group | Source | Choosing a result |
| --- | --- | --- |
| Commands | Pages allowed for the current role, plus appearance, refresh, shortcuts and sign-out actions | Navigates or runs the action |
| Accounts | `accounts` route, matched on the username | Opens the account detail |
| Knights | `knights-all`, matched on knight or account name | Opens the knight |
| Items | `items` search, administrators only | Opens the content catalogue pre-filtered |

`↑`/`↓` move, `PageUp`/`PageDown` jump, `Home`/`End` go to the ends, `Enter` opens
and `Escape` closes. Remote record search starts at two characters, is debounced by
160 ms, cancels the previous request, and never replaces newer results with older
ones.

## Keyboard shortcuts

| Keys | Action |
| --- | --- |
| `Ctrl`/`⌘` + `K` | Open the command palette |
| `?` | Shortcut reference |
| `/` | Focus the page's search box |
| `r` | Refresh page data |
| `t` | Switch theme |
| `Esc` | Close the topmost dialog or drawer |

Shortcuts are ignored while a text field, select or content-editable element has
focus, so typing a knight name never fires them. `?` also appears in the top bar
for pointer users.

## Server identity

Operators used to SSH into the host to read `data/keys/public.txt`; the Overview
page now shows it. The panel is deliberately one value and one action: the
`modulus#exponent` pair the client trusts, shown as a selectable, horizontally
scrollable line, and **Copy key**, which copies that exact value and confirms with
a toast. The key's tooltip carries the SHA-256 fingerprint. It sits below the
metric strip and above the charts, so the value is the first thing below the
process figures rather than the last thing on the page.

The panel reports whether the stored public half still matches the private key
that signs handshakes (`pairMatches`), and raises a warning when it does not:
a `public.txt` replaced while the server is running otherwise publishes a
confident fingerprint for a key clients are not using. Copy uses the key the
private half implies in that state. To rotate, stop the server, remove
`data/keys/private.pk8` and `data/keys/public.txt`, start it again, and hand
clients the new key — every client pinned to the old key must be updated.

- **Never exposed:** the private key, its path and the reverse-proxy secret. The
  panel only reports whether a signing key is stored (`keyStored`). Every API
  answer carries `Cache-Control: no-store`, and `HEAD` answers with the same
  headers and no body.
- **Degrades honestly:** a missing file, a `public.txt` that does not parse, or a
  modulus/exponent pair that cannot be a handshake key all return
  `available: false` with a reason instead of failing the route, and the panel
  re-checks on every refresh so a key generated later grows its actions.
- **Permission:** `VIEWER`. A signed-out request gets `401`, so the fingerprint is
  never public.
- **Action:** copy the public key. The read-only line itself is focusable and
  selectable, so an operator can audit or copy a fragment without another control.

## Terminal surface

Logs and Console share one component, `assets/components/log-stream.js`, and read
as a terminal window rather than a table: a live indicator whose colour is the
stream's real state, a title bar with the visible line count, a monospaced grid
(`time · level · thread · message`), severity in the level column, and a status
line with the key hints and the tail state. Both pages fill the reading area
(`calc(100dvh - 210px)`, at least 62dvh and 260 px) so the surface has the same
weight on either page, and a landscape phone gets the height it actually has.

The severity filter carries the per-level counts (`ERROR (3)`, `All (1000)`), counted
over the search-filtered rows: finding three errors among a thousand lines used to
mean choosing ERROR and counting what was left.

Logs also filters by **Period** (`All time`, `Last 5 minutes`, `Last hour`, `Last 24
hours`): a thousand retained lines span days on a quiet server, and severity plus free
text could not narrow that at all. A line whose timestamp cannot be parsed is never
hidden by a window.

On Logs, every control that shapes the pane is in the address, not just the two the
filters own: `#logs?severity=WARN&search=timeout&period=Last+hour&raw=1&wrap=0` is the
view an operator is looking at, so a copied link arrives at it. `raw` and `wrap` used to
be read from the URL and never written back, which meant a pane could show parsed,
wrapped lines under an address that said otherwise. The Console shares the component but
not this contract: its transcript is browser-local state keyed by no address, so its
raw and wrap toggles are not round-tripped.

Severity is carried by the level column, not by the message: a long warning stays
readable text instead of turning into a gold paragraph. A buffer that crosses
midnight gains a day divider, and `Show raw lines` renders the exact text the
server wrote to `data/logs/latest.log` — the form you grep and paste into a bug
report, including a stack trace's indentation.

| Keys | Action |
| --- | --- |
| `Ctrl`/`⌘` + `F` | Focus the output search |
| `Ctrl`/`⌘` + `L` | Clear the visible output |
| `End` | Resume tailing the newest line |
| `Home` | Jump to the oldest retained line and stop following |
| `p` | Pause or resume the stream |
| `Esc` | Leave the output pane |

The gutter is apparatus, so `time`, `level` and `thread` are not selectable: dragging
a message out of the pane yields the message, not the four columns in front of it.
`Ctrl`/`⌘` + `Shift` + `C` copies just the selection. **More → No wrapping** shows a
stack trace or a long JSON line as one scrollable row instead of reflowing it.

**More → Clear the pane** is the keyboard shortcut's control, because the hint line
that documents `Ctrl+L` is hidden on a phone. Copy and Export hand back what is on
screen: cleared rows are left out, an export names its filters, capacity, dropped and
cleared counts in a header, and the filename carries the page and the time. Typing in
the search field repaints after a 120 ms pause rather than on every keystroke, which
used to rebuild every cached row per letter over a 1000-line buffer.

The status line never claims more than it has: it reports the buffer's capacity
and how many lines were evicted (`1000 of 1000, 42 older dropped`) and names the
file that holds the complete log. `More → Load last 500 from file` reads that file
verbatim, so a fresh page is not limited to the lines logged since the last
restart. **Pause** freezes the view but not the tail: polling continues, the
status line counts what arrived (`12 new`) and resuming shows it, which is what
distinguishes a pause from a silent gap. A failed read backs off instead of
polling every four seconds, and the first success clears the error notice.

The palette is theme-aware: both Catppuccin and Tortodrone have their own
ink-black pane and severity semantics, all driven by `--term-*` tokens so contrast
can be checked per theme.

Filtering never moves the viewport: the stream stays anchored while rows are
hidden, and a **Jump to latest** pill appears when new rows arrive off-screen.
Console keeps its prompt inside the shell and nothing else: a `tortodrone$` label,
a borderless monospace field, and **Enter** to submit — there is no Run button, no
Help button and no row of command chips, because `help` prints the command list into
the transcript where a shell prints it. `Tab` completes from the server's route list,
prints its candidates when they are ambiguous, and completes the argument of the one
command that has one — `kick` — from the live session ids the server reports, so a
kick no longer asks the operator to go and look an id up; `↑`/`↓` walk the history, `Ctrl`/`⌘` + `R` searches it backwards from
whatever is in the prompt (each press steps to an older match, and reaching the
browser with it used to reload the page mid-command), `Esc` clears the prompt, and
`Ctrl`/`⌘` + `L`/`C` clear the screen and cancel — `Ctrl`/`⌘` + `Shift` + `C` is the
copy-selection chord and no longer cancels, which used to destroy a half-typed
command at the same moment it copied. An answer longer than 24 lines folds, naming
how many lines it hid, and *Export* carries all of them. A structured answer is
printed as aligned key/value pairs rather than prettified JSON, so `status` fits on
one screen. **More → Show** decides what the pane is about: `Answers` (the default,
because the server log underneath is context and used to bury every answer under live
INFO chatter), `Server log`, or `Both`. A running command pulses its answer row and
blinks a block cursor beside the prompt label — a still `running…` reads as a hang —
and a second submit names the command that is still out instead of saying "a command
is already running". The connection state lives in the status line (`● Connected`,
`Executing…`, `Disconnected · reason`), and an expired session puts **Sign in again**
there instead of printing *Sign in required* after every command.

`Ctrl`/`⌘` + `L` is the only clear, and it is one number: the visible lines leave the
pane, the note says how many are held, and **Restore** puts the same lines back — the
bar, the note and the pill count the same set.

The severity and search fields write their state back into the address, so
`#logs?severity=WARN&search=timeout` is what the operator is looking at and can be
copied. `?raw=1` describes the same pane and survives a typed search.

The bar also carries the three pages a line is *about* — **Audit trail**, **Accounts**,
**Overview** — because a warning names a subsystem and stops there. A fresh console
opens at its prompt with one banner line counting the commands, naming the keys, and
linking the pages that explain an answer; every console answer that maps to a page ends
with that link.

Deep links land on a filtered view, and every filter is written back: `#logs?severity=WARN`,
`#logs?search=timeout`, `#console?search=status`, `#accounts?search=aelric`,
`#knights?status=Online`, `#mailing?search=hi`, `#content?search=Key` and
`#depot?availability=Enabled` all round-trip, so what the operator sees is what the
address says. A table owns its address through `address: true` (`assets/components/table.js`):
it reads `?search=` and each filter on arrival and replaces them on every edit, keeping
the params it does not own, so `?tab=`, `?offer=` and `?campaign=` survive. The address
outranks a filter this browser remembered, so a shared link shows what it names; a read
that lands writes the view it is showing into the address, so a copied link is never a
promise the screen does not keep. An in-app link drives the same controls a cold load
does, because the table read its parameter when it was built. A record
that is named in the address — an offer, a station, a letter, an audit event, a knight,
the key card — is applied on a cold load, after the page has attached, and scrolled
into view; a record that no longer exists is reported in the frame the page paints.

Every write that gets no answer says so: the dialog error line and the page notice
report that the change may or may not have been stored, and ask the operator to reopen
the record before saving again, because a lost reply is not a lost write.

## Uplink News

The banner editor is a preview plus a picker, and the **Image library** under it is a
grid of what is actually on the server: each tile shows the image itself, its path, its
pixel size and weight, and which pack slots already use it, so replacing an image is an
informed act rather than a guess about a filename. A tile in the current slot is
selectable straight from the grid, and a tile that cannot be used says why (too large
for the slot, or no pack saved yet) instead of going quiet.

A banner's **Link URL** must start with `https://` (or `http://` for an internal host):
the client opens it in the player's browser, so anything else is refused in the field
and blocks the publish with the offending pack slots named.

## Audit Trail

The trail filters by outcome and by **Period** (`All time`, `Last hour`, `Last 24
hours`, `Last 7 days`). The buckets exist because the table's filters are selects: a row
whose timestamp cannot be parsed is never folded into "recent", it stays visible under
every window.

The server sends 500 rows at a time (`WebIdentityStore.audit(before)` takes the oldest id
already on screen as an exclusive cursor), and **Older events** walks back through the
trail, so nothing is unreachable: it disables itself the first time a page comes back
short, which is how the end is known without counting the table. The line above the list
says how many events are loaded, over how many pages, and whether that is all of them.

**Pause** stops the ten-second tick, because it re-inserted rows at the top under whoever
was reading them. It does not stop the operator: the header's Refresh, the refresh a write
triggers, and Older events all still read, because a button that says Refresh has to
refresh. The shell keeps those apart — `refresh(polled)` sends the tick to the page's own
`poll()` and the operator to its `refresh()` (`app.js:196-250`) — so the page's copy says
"the ten-second tick is paused", not "polling is off". A read replaces the newest page and
keeps every page paged back to.

## Player Mail

Compose is a three-mode editor: windowed **Campaign**, **Direct** mail to one
knight, and the **Welcome** letter. Each mode shares the same layout — a template
row (Maintenance, Compensation, Event, Season start), sender and subject, the body
beside a preview, and attachments.

- **Window presets:** **Start now**, **Start in 1 hour** and **Start tomorrow** fill
  the delivery window and never save: the old "+1 hour" button armed a thirty-day
  mailing behind a click that described something else.
- **Preview:** an **In-game** view (what the client renders) and a **Plain text**
  view (the exact characters that reach the mailbox, including the `%uplink:~`
  prefix on both subject and body for the stock senders), toggled with pressed-state
  buttons and kept current while typing. Both views show the stripped value and say
  that leading and trailing spaces are removed on save, and each footer names the
  reach (`N knights receive it once`) and the window in local time.
- **Counters:** subject and body carry `n / limit` counters that turn into the
  error style past the limit, and the server's own refusal sentence is printed
  verbatim rather than a generic conflict.
- **Recipients:** direct mail offers a filterable knight list with per-knight
  checkboxes, *Online only* / *Game masters* quick filters, *Select all shown*
  (the rendered rows only) and a separate *Select all N matching* when more are
  hidden, plus *Send a copy to me*. A batch asks for confirmation naming the
  recipient count, reports per recipient, toasts an error when any recipient was
  refused, and offers *Retry refused* / *Resume remaining* without ever re-sending
  a knight that already received the letter in that batch.
- **Delivered:** each campaign shows `delivered of population`, and *Inspect
  recipients* reads `mail-deliveries` for the per-knight roster, then diffs it
  against the knight roster it already holds for the reach figure — the count alone
  cannot answer "who did not get it", so a **Show not yet delivered (N)** toggle does,
  with its own export. The diff joins on the knight *name*, not on an id: a delivery
  row's `worldId` is `world_characters.world_id` (a global sequence) while a roster
  row's id is `knights.id` (restarts at 1 per account), and `global_knight_names`
  makes the name unique across accounts. Comparing the two id spaces reported every
  second account's first knight as already delivered. When the roster could not be
  read the dialog says that instead of printing an empty list as if everybody had
  received it.
- **Reading a letter:** the campaign name opens a read-only rendering of what was
  sent, with its window, sender, gifts and counts, and an *Edit* action inside.
- **Scheduling, not drafts:** there is no draft state. A campaign is either armed
  for a window or cancelled, and *Save* sets a deliberate future window and says so;
  saving a window that already contains now asks a second time. *Duplicate* opens a
  new mailing with an empty future window and warns that the original already
  reached its recipients.
- **Proof read:** *Send a copy to me* exists in every compose mode, not just a
  direct send: a campaign reaches every knight, so the letter can be read in the
  client first. A copy is the letter alone — the composer's crowns, energy and gifts
  are stripped, because a proof read must not pay out a campaign's loot — and it
  still demands a subject and a body. The roster it picks from is the one the reach
  count already loaded; *Retry the knight count* stays on screen and names the time
  of the read it is showing, since a 30-second poll does not re-read every knight.
- **Receipts:** a browser-local panel (localStorage, capped, clearable — clearing it
  is a local act and says so instead of reporting a server save) keeps the
  per-recipient result of every direct send, so a batch is explainable after the
  toast is gone; the authoritative server counts live under *Inspect recipients*.

## Preferences

`Preferences` (Operations) stores browser-local settings; they are never sent to
the server and are not part of a WebUI identity.

| Preference | Effect |
| --- | --- |
| Theme | Tortodrone (default) or Catppuccin. The boot script applies it before first paint; `t` and the command palette switch it too. |
| Motion | `Reduce` disables animation and smooth scrolling regardless of the OS setting. |
| Header clock | Date only, or date and local time. |
| Rows per page | Default page size for new tables (15/30/60/120); each table also remembers its own. |
| Refresh frequency | Multiplies the polling interval so metered links can poll less often. |

Every destructive action asks for confirmation in the interface; that is not a
preference, and the server keeps its own required confirmation regardless.

`Copy diagnostics` copies the version, role, session expiry, preference snapshot,
user agent and viewport for support reports.

## Tables

Every list is one `DataTable`: sticky header, sortable columns with `aria-sort`,
optional row selection with a bulk bar, filters, paging, and a CSV/JSON export of
whatever the current filter leaves. **Rows per page** is the table's own: it is
remembered beside its sort and filters, and Preferences owns only the default a *new*
table starts at. A pager that wrote the global preference silently reset the
operator's default for every other table, which is the opposite of what Preferences
promised.

The item catalogue is read through a 24-tile window, so it carries two things the
window cannot: its own **Export**, which walks the server's 100-row pages and
downloads the filtered set as CSV up to 1,000 rows (the toast says how many of how
many it wrote — a partial file that claims to be whole would be worse than no file),
and **As a table**, which reads the same walk into the shared sortable table with
its own CSV/JSON export. Both walks carry an `AbortController` that leaving the page
aborts, and the table is retired when the search or the category changes, so the set
on screen is never one the controls above no longer describe.

Every record list shares one table component and therefore one behaviour set:

- **Search** is debounced and matches every field; `Esc` in the search box clears it.
- **Filters** per table combine with search; both persist per table.
- **Sorting** works from the column header buttons and from the sort control in the
  paging bar, which is what mobile keeps available when the header collapses. Every
  list that has a spine declares the order it opens in — username, item, station,
  newest-first for the trail — so a header reports the column it is really sorted by
  instead of `none` on all of them. The direction is drawn as a triangle, not typed: at
  12px an `↑` and a `↓` photograph as the same tick, and a mark that names the column
  without naming the way is worse than no mark. It sits only on the sorted column, and
  every header holds the same 7px slot so no label moves when the order changes.
- **Paging** shows `N records of M · page X / Y` in a polite live region.
- **Selection** adds a checkbox column, a header select-all with an indeterminate
  state, and a bulk bar with the actions that page supports (for example
  disconnect selected sessions, copy usernames, grant or remove Game Master).
- **Export** writes CSV or JSON of exactly the filtered rows, not just the page.
  CSV quotes separators, doubles inner quotes, keeps line breaks and prefixes
  values that a spreadsheet would treat as a formula.
- **Empty states** explain why a list is empty and offer the next step (clear the
  filters, or refresh), instead of a bare "no records" line.

## Feedback and safety

- Writes report through transient toasts: success expires after six seconds, errors
  after twelve and are announced as `alert`. The region caps four messages, pauses
  its timer on hover or focus, dismisses with `Esc`, and every toast names its
  dismiss button.
- Long-running reads show a skeleton with `aria-busy` so layout does not shift and
  assistive technology hears one pending update instead of a half-built page.
- A failed page render shows the message, the retry action, and a notice; nothing
  leaves the operator on a blank screen.
- Unsaved editors, navigation away with pending edits and sign-out all confirm
  before discarding a draft. Revision conflicts keep the draft and explain how to
  reload the record.
- **Red means "this destroys something you cannot get back"**, not "this concerns money".
  Stopping the listener, kicking a session, cancelling a promotion, cancelling an
  auction listing and deleting a record are `danger`. Settling every expired auction is
  not: settlement is the exchange working as designed, and a control that is red every
  day is a control operators stop reading. What a `danger` submit must carry is a label
  that names the deed.
- A confirmation says what it destroys, and carries the weight of it: the submit is
  a `danger` control, its label names the deed (`Stop the listener`, `Disconnect`,
  `Cancel promotion`) instead of a generic "Confirm", and a destructive dialog parks
  focus on Cancel so Enter on an untouched form cannot perform it. Learned from a
  review: a retype field was the only signal for destructiveness, so stopping the
  listener, kicking every selected session and cancelling an auction all promoted the
  same gold button as a save. The Game Master grant is behind this too — it used to
  post on a single click of a switch.
- A long sequential run reports progress (`Sending 8 of 200…`) and can be stopped:
  **Stop** beside the progress line ends the batch after the request in flight, keeps
  every outcome already recorded, and leaves the remaining knights listed for
  **Resume**. A modal that refuses Close, Cancel and Escape while it works, with no
  control that ends it, is a trap.
- A *conditional* control stays focusable and explains itself: `aria-disabled` plus a
  visible note and `aria-describedby`, instead of a native `disabled` that removes the
  control and its reason from the tab order together. Three cases keep native `disabled`,
  because none of them has a reason a focus stop would deliver: an `<option>`, which no
  browser exposes as focusable at all; a control switched off only while a request is in
  flight (`aria-busy` carries that, and the progress line says what is happening); and a
  control whose off-state is the design itself — a pager's Previous on page one, where
  the disabled end is the convention and the sentence would be noise.
- One empty-state component for the whole application: icon, title, message, and — when
  there is something the operator can do from there — the action that fills the region.
  A bare "No items match these filters." line is a dead end; the catalogue, the gear grid
  and the receipt list offer the reset that makes something match. Where the honest
  answer is "nothing can be done here" (Battle Sprites are earned in game), the message
  says exactly that instead of inventing a button.
- Every date in a table, a fact list or a dialog carries its zone
  (`03/12/2026, 14:00 GMT+3`). The server stores promotion and auction windows as Unix
  milliseconds UTC, so a bare `14:00` meant two different instants to the operator who
  scheduled it and the colleague they sent the link to. The terminal gutter prints
  `HH:MM:SS` by design — a zone on every one of a thousand log lines is noise, and the
  log's own status row names the file it came from — and the header clock follows the
  browser's short locale form.
- Sessions expire after 30 idle minutes; at five minutes remaining the interface
  warns once and a refresh extends it. Expiry returns to the sign-in screen and
  announces the reason.

## Session and transport

`WebUI Access` (Operations) opens with a read-only facts panel for the signed-in
session: identity, role, session expiry with the time left, cookie protection for
the current scheme, and the checks the server applies to every request (Host,
Origin, CSRF token, role) plus the login rate limit and the audit guarantee. An
administrator can therefore confirm the transport in use without reading
`config/server.yml` or the proxy configuration.

## Responsive behaviour

| Width | What changes |
| --- | --- |
| 1560 px and up | Content column capped so tables and charts keep a readable measure. |
| 901–1559 px | Sidebar stays fixed; two-column page sections keep their columns. |
| 900 px and below | Sidebar becomes a drawer with a scrim and `inert` background, and the top bar's **Menu** button opens it (`(max-width: 900px)` in both `layout.css` and `app.js`); tables become labelled cards (`.data-table` at `components.css`, the 900 px block); search takes its own line; table card actions reach a 36 px target. |
| 1000 px and below | The search trigger keeps its words and drops the `Ctrl K` chip, which a phone cannot press. |
| 360 px | The narrowest supported width: every page fits without horizontal scrolling, which `shell.js` asserts. |

## Accessibility

- Landmarks: banner, navigation, main, and one `h1` per page; the page's live
  region announces navigation.
- The palette is a real combobox/listbox pair with `aria-activedescendant`; tables
  carry captions, `scope="col"` headers, `aria-sort`, and a labelled scroll region
  so keyboard users can pan wide tables.
- Every control has a programmatic name, including icon-only buttons (menu,
  shortcuts, dismiss).
- Focus is visible on every interactive control, in both themes, at every
  specificity. Exactly three rules set `outline: none`, each with something standing in
  its place: the page heading, which is focused only so a screen reader starts reading
  from the top and whose name is announced through `#page-status`; the palette's field,
  where the ring moves to the field's frame; and the console's prompt, where the ring is
  the border colour. The skip link lands on `#main` with an inset ring on
  `:focus-visible` — never bare `:focus`, which is what a click into the page also
  triggers. In card mode a table's header row is clipped to keep the layout tight, so
  the whole row reappears through `:focus-within` when a sort button takes focus: a
  keyboard user never stops in a 1x1 box. Dialogs trap focus; the mobile drawer closes
  with Escape and restores focus to the button that opened it.
- A control that cannot act *because of context* stays focusable and says why
  (`aria-disabled` plus a description) rather than disappearing from the tab order as
  `disabled` would. Its `unavailable` reason names the actual blocker, never the generic
  "not available on this server yet": an off-by-default row action refused with that
  sentence was telling the operator the feature does not exist when his own row was the
  problem.
- A tab strip names the region it switches: `aria-controls` on each tab, and
  `role="tabpanel"` with `aria-labelledby` following the selection.
- Status text does not rely on colour alone, both themes meet AA contrast, and
  `prefers-reduced-motion` plus the preference switch disable animation. A rule between
  two rows of data is a boundary, not decoration, so it has its own token: `--line-data`
  clears 3:1 against the surface and against the zebra tint, where the decorative
  `--line` sat at 1.71.
- Row-selection checkboxes draw at 16px inside a 24px label, and table rows are
  spaced further apart than the 24px target circle, so WCAG 2.2 target size is
  met by the spacing exception while dense tables stay readable. On a coarse pointer
  every button, select and `button.link`/`.button.link` reaches 44px, named explicitly in
  the last sheet: the link variants set their own 24px floor at a higher specificity, and
  the drawer's page links are padding-only, so both had to be listed or the touch step
  silently skipped them. A bare `a.link` — a scene name in a cell, a drill-down in a
  sentence — keeps 24px, which is WCAG 2.5.8's minimum for a pointer target and the most a
  line of prose can give it.
- Charts are focusable images with a description: arrow keys, Home and End move
  through samples and the readout becomes a polite live region only while the
  keyboard is inspecting, so pointer movement never floods a screen reader.
  Telemetry is drawn as a smooth `--chart-series` line over a gradient area with
  horizontal grid rules only; `--chart-secondary` marks the comparison series.
  Both tokens are theme-owned, so Catppuccin renders teal/mauve and Tortodrone
  renders gold/cyan.

## Frontend structure

| Path | Responsibility |
| --- | --- |
| `assets/app.js` | Shell: routing, session lifecycle, polling, palette wiring, shortcuts |
| `assets/router.js` | Hash routing and deep links |
| `assets/api.js` | Typed request boundary, CSRF, abort and deadline handling |
| `assets/prefs.js` | Preference schema, coercion, storage and document mirroring |
| `assets/pages/*.js` | One controller per page: `mount(ctx)` returns `{node, refresh, dispose, navigate, interval}` |
| `assets/components/table.js` | Keyed data table with search, filters, paging, selection and export |
| `assets/components/palette.js` | Fuzzy scoring, ranking and the command palette dialog |
| `assets/components/toast.js` | Single notification region and toast lifecycle |
| `assets/components/states.js` | Skeletons, empty states and error states |
| `assets/components/export.js` | Pure CSV/JSON serialisation and download |
| `assets/components/dialog.js` | Modal editors, confirmations and dirty-state guards |
| `assets/css/` | `tokens.css` variables, then `base`, `layout`, `components`, `pages` |

Conventions worth keeping:

- A page mounts once; `refresh()` updates existing nodes and never rebuilds
  persistent controls.
- Rendering goes through `el()`/`setAttribute`; response text is never assigned as
  HTML.
- Pure helpers (`score`, `rank`, `filterRows`, `sortRows`, `toCsv`, preference
  coercion) stay free of DOM access so `node:test` can cover them.
- A `const`/`let` used while the page mounts is declared *above* its first use.
  `mount()` runs top to bottom, so a declaration placed after the panel list or the
  block that calls it is a temporal-dead-zone `ReferenceError` at mount: it has
  already cost this UI two dialogs that opened nothing at all, and no linter is in
  the build to catch it.

## Payload and caching

Counted from this tree at the commit that last touched `frontend/`, which is what
`buildWebui` copies: `wc -c` for the raw size and `gzip -9 | wc -c` for the
compressed one. Every number below was measured *after* the assets stopped moving —
a table re-measured before the commit that edited the same files is off by exactly
the code it was supposed to describe.

| Asset class | Raw | Gzip | Transport |
| --- | --- | --- | --- |
| `index.html` | 10 160 B | 2 377 B | Gzip when the browser offers it, `must-revalidate` |
| JavaScript (33 files under `frontend/assets`) | 646 684 B | 168 028 B | Gzip, `must-revalidate`, ETag per encoding |
| CSS (5 sheets) | 147 834 B | 37 579 B | Gzip, `must-revalidate`, ETag per encoding |
| Icons and artwork (5081 files, 87 MB on disk) | median icon 16 kB | n/a | `max-age=31536000, immutable` for the content-hashed `assets/game/icons/<hash>` paths |

The JavaScript column is every module at once, which no single page loads: a
VIEWER never fetches `mailing.js`, and there is no bundler, so the number grows
with each page module. Gzip is applied to text assets over 1 kB: `app.js` alone
goes from 37 213 to 11 008 bytes. The ETag carries the encoding (`…-gzip`) and `Vary: Accept-Encoding`, so a
cache never serves one representation for the other and revalidation still
answers `304`. Every non-hashed asset revalidates, so an upgrade is visible on the
next load without a hard refresh; hashed artwork is cached for a year. Only the
icons a page renders are requested, and item images are `loading="lazy"` with
`decoding="async"`.

## Endpoint coverage

The admin API is a fixed route vocabulary on the server. `src/test/webui/routes-test.mjs`
reads that vocabulary out of `AdminService.java`, `AdminHandler.java` and
`AdminConsole.java` and fails when the WebUI never names a route, so a new backend
operation cannot ship without a control. The mapping in brief:

| Route group | Where the WebUI uses it |
| --- | --- |
| `status`, `logs`, `metrics` | Overview, Logs, Players, the connection strip |
| `identity` | Server identity panel on Overview |
| `accounts`, `account-*`, `knights`, `knights-all` | Accounts, Knights |
| `knight`, `knight-state`, `knight-view`, `knight-delete`, `knight-progress`, `knight-currency` | Knight profile, progression and currency editors |
| `items`, `item`, `item-definitions`, `accessories`, `item-preview` | Item picker, catalogue, gear editor |
| `item-grant`, `item-quantity`, `item-accessory`, `item-modifiers`, `equipment` | Grant, inventory and loadout actions |
| `depot`, `depot-save`, `promotion-*` | Supply Depot |
| `auctions`, `auction-*`, `deliveries` | Auction House |
| `mail`, `mail-*`, `welcome`, `welcome-save` | Player Mail |
| `news`, `news-*` | Uplink News |
| `content`, `catalogue` | Content Catalogue |
| `webui-users`, `webui-user-*`, `audit` | WebUI Access, Audit Trail |
| `console`, `start`, `stop`, `restart`, `shutdown`, `kick` | Console page and the Overview lifecycle strip |
| `setup`, `login`, `logout`, `session` | First-run setup and the sign-in shell |

## Verification

```sh
node --test src/test/webui/*-test.mjs
node src/test/webui/assets-check.mjs dist/webui
node --check frontend/assets/app.js
./gradlew testUnit -PunitTests=WebUiTest,WebAdminServiceTest,WebIdentityStoreTest
./gradlew integrationWebUi integrationConsole
```

`responsive-test.mjs` pins the mobile contract (card rows with `data-label`,
hidden header row, 36 px targets from the `--control*` tokens at default size and
44 px on a coarse pointer, and no `min-width`/`min-inline-size` wider than 360 px
in any of the four sheets) and
`routes-test.mjs` pins endpoint coverage; both fail loudly if a refactor drops
either. The glob is deliberate: a written-out list once named a file that did not
exist, and `node --test` exits 0 when a path is missing, so the documented command
verified less than it claimed. The browser `shell.js` group ends with a sweep over every navigable page,
checking one visible `h1`, a caption and scoped headers on each table, unique ids,
no horizontal overflow, and the session facts on WebUI Access.

The test modules import the shipped `frontend/assets/` files by relative path, so
they resolve from the repository root without a build step. Browser regression
groups (`acceptance.js`, `refinements.js`, `shell.js`, `icons.js`) run inside a
disposable fixture; copy them beside the fixture's `assets/` directory and never
against live player data.

## Icons and artwork

Every glyph and item image is copied from the installed game client by the server
at first start (`buildWebui` overlays a prepared pack from `local/webui-assets-optimized`
or `-PwebuiAssets=<path>`). `assets/components/symbols.js` prefers the native symbol
(`nav-<name>`, `slot-<name>`, `brand`) and tints it with the surrounding text colour,
so navigation glyphs follow the theme's link ink, the active page follows the
theme's brand ink and destructive actions follow red in both themes. The small set of source-owned SVG outlines in
that module is a fallback for symbols a prepared pack does not contain — for example
the palette magnifier and the keyboard hint — and disappears as soon as the pack
exports a matching name.

A server started with `--skip-client-assets`, or one on a host with no game install,
has an **empty** pack: `manifest.json` reports `"source": "No local client assets
prepared"` and every `symbol()` call answers `null`. That is a supported state and it
costs only the glyphs — every control keeps its word, and the sidebar keeps its
section headings and page titles. It used to cost the whole navigation: the frame
called `setAttribute` on that `null` inside `flatMap`, so `replaceChildren` never ran
and the shell came up with an empty sidebar and no way between pages. The sidebar now
builds its groups in `navigationGroups()`, survives a missing glyph, and falls back to
word-only links plus a reported error if the build ever throws, because a navigation
that throws takes the operator's only route out of the page with it.
