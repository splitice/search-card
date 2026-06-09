# Search Card

A Lovelace card for Home Assistant that enables quick entity searching, local service shortcuts, and customizable actions.

![Demo of card](images/demo.gif)

## Features

- 🔍 Quick entity search within the Home Assistant frontend
- 🌐 Searchable shortcuts for local network services
- ⚡ Custom actions with regex-based matching
- 🎯 Regex-based entity filtering
- 🛠️ Full Lovelace visual editor support
- 📋 Configurable result limits and placeholder text

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
| max_results      | integer  | 10                  | Maximum number of search results to display |
| search_text      | string   | "Type to search..." | Custom placeholder text                     |
| actions          | object   | optional            | Custom action definitions                   |
| local_services   | object   | optional            | Searchable local service links              |
| included_regex   | string[] | `["."]`             | Regexes that an entity match must match first |
| excluded_regex   | string[] | optional            | Regexes removed after include filtering     |

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

### Visual Editor

The card supports the Lovelace visual editor for all current options, including:

- `search_text`
- `max_results`
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
- "Show all" results button
- Additional action types
