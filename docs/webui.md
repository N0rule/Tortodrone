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
reference and the clock. Below 760 px the sidebar becomes a drawer with a scrim,
Escape closes it and focus returns to the menu button.

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

## Preferences

`Preferences` (Operations) stores browser-local settings; they are never sent to
the server and are not part of a WebUI identity.

| Preference | Effect |
| --- | --- |
| Theme | Dark or light. The boot script applies it before first paint. |
| Density | Comfortable or compact row and panel spacing. |
| Motion | `Reduce` disables animation and smooth scrolling regardless of the OS setting. |
| Header clock | Date only, or date and local time. |
| Rows per page | Default page size for new tables (15/30/60/120); each table also remembers its own. |
| Refresh frequency | Multiplies the polling interval so metered links can poll less often. |
| Destructive actions | Toggles the interface confirmation prompt. The server keeps its own required confirmation. |

`Copy diagnostics` copies the version, role, session expiry, preference snapshot,
user agent and viewport for support reports.

## Tables

Every record list shares one table component and therefore one behaviour set:

- **Search** is debounced and matches every field; `Esc` in the search box clears it.
- **Filters** per table combine with search; both persist per table.
- **Sorting** works from the column header buttons and from the sort control in the
  paging bar, which is what mobile keeps available when the header collapses.
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
- Sessions expire after 30 idle minutes; at five minutes remaining the interface
  warns once and a refresh extends it. Expiry returns to the sign-in screen and
  announces the reason.

## Accessibility

- Landmarks: banner, navigation, main, and one `h1` per page; the page's live
  region announces navigation.
- The palette is a real combobox/listbox pair with `aria-activedescendant`; tables
  carry captions, `scope="col"` headers, `aria-sort`, and a labelled scroll region
  so keyboard users can pan wide tables.
- Every control has a programmatic name, including icon-only buttons (menu,
  shortcuts, dismiss).
- Focus is visible everywhere, moves to the page heading on navigation, and is
  trapped by dialogs; the mobile drawer closes with Escape and restores focus.
- Status text does not rely on colour alone, both themes meet AA contrast, and
  `prefers-reduced-motion` plus the preference switch disable animation.

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

## Verification

```sh
node --test src/test/webui/news-test.mjs src/test/webui/chart-test.mjs \
  src/test/webui/table-test.mjs src/test/webui/mailing-test.mjs \
  src/test/webui/table-filter-test.mjs src/test/webui/palette-test.mjs \
  src/test/webui/export-test.mjs src/test/webui/prefs-test.mjs
node --check frontend/assets/app.js
./gradlew testUnit -PunitTests=WebUiTest,WebAdminServiceTest,WebIdentityStoreTest
./gradlew integrationWebUi integrationConsole
```

`src/webui` is a symlink to `frontend/` so those test imports resolve from the
repository root. Browser regression groups (`acceptance.js`, `refinements.js`,
`shell.js`, `icons.js`) run inside a disposable fixture; copy them beside the
fixture's `assets/` directory and never against live player data.

## Icons and artwork

Every glyph and item image is copied from the installed game client by the server
at first start (`buildWebui` overlays a prepared pack from `local/webui-assets-optimized`
or `-PwebuiAssets=<path>`). `assets/components/symbols.js` prefers the native symbol
(`nav-<name>`, `slot-<name>`, `brand`) and tints it with the surrounding text colour,
so navigation glyphs follow cyan, the active page follows gold and destructive
actions follow red in both themes. The small set of source-owned SVG outlines in
that module is a fallback for symbols a prepared pack does not contain — for example
the palette magnifier and the keyboard hint — and disappears as soon as the pack
exports a matching name.
