# Search Card

A Lovelace card for Home Assistant that enables quick entity searching, local service shortcuts, and customizable actions.

![Demo of card](images/demo.gif)

## Android home-screen widget

A native Kotlin Android app and search-bar widget live in [`android/`](android/README.md). It syncs this card's dashboard configuration, supports cached offline search, and connects only while the search panel is open. See the [Android build and installation instructions](android/README.md).

## Features

- 🔍 Quick entity search within the Home Assistant frontend
- 🌐 Searchable shortcuts for local network services
- ⚡ Custom actions with regex-based matching
- 🎯 Regex-based entity filtering
- 🛠️ Full Lovelace visual editor support
- 📋 Scrollable search dropdown with every match and customizable placeholder text

## Installation

### Prerequisites

- Home Assistant
- [card-tools](https://github.com/thomasloven/lovelace-card-tools)

### Option 1: HACS (Recommended)

1. Search for "Search Card" in the HACS store
2. Install and follow the HACS prompts

### Option 2: Manual Install

1. Download `search-card.js`
2. Copy it to `config/www/search-card/` (create directory if needed)
3. Add to `ui-lovelace.yaml`:

```yaml
resources:
  - url: /local/search-card/search-card.js?v=0
    type: module
```

### Option 3: Git Install

```bash
# Clone into your www directory
git clone https://github.com/postlund/search-card.git
```

Then add the same resource reference as in Manual Install.

## Configuration

### Basic Example

```yaml
type: custom:search-card
max_results: 10
search_text: "Search entities..."
excluded_regex:
  - battery
```

### Available Options

| Name             | Type     | Default             | Description                                 |
| ---------------- | -------- | ------------------- | ------------------------------------------- |
| max_results      | integer  | 10                  | Approximate visible rows before scrolling; never limits total matches |
| search_text      | string   | "Type to search..." | Custom placeholder text                     |
| actions          | object   | optional            | Custom action definitions                   |
| local_services   | object   | optional            | Searchable local service links              |
| included_regex   | string[] | `["."]`             | Regexes that an entity match must match first |
| excluded_regex   | string[] | optional            | Regexes removed after include filtering     |

### Search dropdown and scrolling

Results appear in a scrollable dropdown over the existing dashboard; typing does not expand the card or move later rows. All matching entities, pages, and local services are available. `max_results` controls the approximate number of rows visible before scrolling (default 10), capped by available viewport space. It never limits the total matches. Change **Max initial results** in the visual editor to make the dropdown shorter or taller. Rows load in batches of 100 as you scroll; **More results** provides a keyboard-accessible alternative. The count shows the full number of matches.

The dropdown matches the search field's width and fits the visible viewport, including mobile keyboard changes. When needed, the dashboard scrolls just enough to leave space below the field. If it cannot scroll further, the dropdown can open above the field. It uses the browser's [Popover API](https://developer.mozilla.org/en-US/docs/Web/API/Popover_API/Using) to remain above dashboard containers that clip their content. Use a current browser or Companion WebView. Click outside or press Escape to dismiss, and click the field to reopen. Arrow Down moves focus into the results; entity and link rows support Enter/Space.

### Search matching

Search uses case-insensitive literal words in any order. Every word must occur in the entity ID, friendly name, or associated device name; partial words work across fields. For example, `Rumpus temp` finds both a temperature entity on the “Rumpus Motion” device and “Rumpus Average Temperature”. Device names prefer user overrides and require registry access; IDs and friendly names remain searchable if metadata is unavailable. Blank queries return no results.

Local services and navigation pages use the same word matching across their existing search fields. Typed punctuation is literal, so typed regex expressions no longer act as patterns. Configured filters and action triggers still use regex.

### Regex Filtering

The card filters Home Assistant entity results with `included_regex` first and then `excluded_regex`.

- `included_regex` defaults to a single `.` entry, which means all entity matches are included unless you override it.
- `excluded_regex` is applied after include filtering.
- Regex filtering applies to both `entity_id` and `friendly_name`.

Include only light and switch entities:

```yaml
type: custom:search-card
included_regex:
  - "^light\\."
  - "^switch\\."
```

Exclude battery-related entities after including everything:

```yaml
type: custom:search-card
excluded_regex:
  - "battery"
```

### Custom Actions

Actions allow you to define service calls triggered by regex matches. Example:

```yaml
type: custom:search-card
actions:
  - matches: '^toggle (.+\..+)'
    name: "Toggle {1}"
    service: homeassistant.toggle
    service_data:
      entity_id: { 1 }
```

### Local Services

Local services let you search configured network services and open them in a new tab directly from the card.

```yaml
type: custom:search-card
local_services:
  services:
    - name: Sonarr
      url: http://sonarr.local:8989
      icon: mdi:television-classic
      aliases:
        - tv
        - series
        - downloads
        - arr
      category: media
    - name: Plex
      url: http://plex.local:32400/web
      icon: mdi:plex
      aliases:
        - movies
        - tv
        - media
      category: media
```

Each service supports these fields:

| Field    | Type     | Required | Description                                   |
| -------- | -------- | -------- | --------------------------------------------- |
| name     | string   | yes      | Display name and primary search text          |
| url      | string   | yes      | Link opened when the result is clicked        |
| icon     | string   | no       | MDI icon shown in the result row              |
| aliases  | string[] | no       | Additional search terms                       |
| category | string   | no       | Grouping label that is also included in search |

### Navigation Pages

The card automatically searches Home Assistant's registered sidebar pages, including Energy, Settings, and dashboards. Search by page title or URL path; `dashboard` also finds dashboard entries. Built-in pages use English display names, and custom dashboard titles are preserved. No card configuration is required.

Click a navigation result (or use Enter/Space) to open it in the current Home Assistant frontend. Results appear after priority entities and local services, followed by ordinary entities. All matches remain available by scrolling. Entity include/exclude filters apply only to entities. Untitled, internal, and server-hidden sidebar entries are excluded, and Home Assistant supplies only pages available to the signed-in account. The Android widget caches the same pages and opens them through Companion, with a browser fallback.

### Visual Editor

The card supports the Lovelace visual editor for all current options, including:

- `search_text`
- `max_results` (initial dropdown size)
- `included_regex`
- `excluded_regex`
- `actions`
- `local_services.services`

## Troubleshooting

If you encounter issues:

1. Clear browser cache
2. Restart Home Assistant
3. Verify card-tools is properly installed
4. Check your configuration syntax

For bug reports, please [create an issue](https://github.com/postlund/search-card/issues) with:

- Your configuration
- Home Assistant version
- Browser and version
- Error messages (if any)

## Roadmap

Planned features:

- Entity exclusion list
- Additional action types

## Browser regression tests

From the repository root, with Node 22+:

```sh
npm ci
npx playwright install --with-deps --no-shell chromium
npm test
```

Tests run the real card with Lit and stub Home Assistant components in desktop and mobile Chromium. They check dropdown placement above clipped dashboard content, mobile scrolling, resizing, dismissal, keyboard selection, and scrolling through 250 results. They require no Home Assistant credentials. JavaScript/Kotlin search comparisons remain part of the [Android checks](android/README.md).
