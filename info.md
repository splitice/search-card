# Search Card

A Lovelace card that enables quick entity searching, local service shortcuts, and customizable actions.

## Features

- 🔍 Quick entity search within the Home Assistant frontend
- 🌐 Searchable shortcuts for local network services
- ⚡ Custom actions with regex-based matching
- 🎯 Regex-based entity filtering
- 🛠️ Full Lovelace visual editor support
- 📋 Configurable result limits and placeholder text

## Prerequisites

- Home Assistant
- [card-tools](https://github.com/thomasloven/lovelace-card-tools)

## Using the card

### Options

| Name             | Type     | Default             | Description                                 |
| ---------------- | -------- | ------------------- | ------------------------------------------- |
| max_results      | integer  | 10                  | Maximum number of search results to display |
| search_text      | string   | "Type to search..." | Custom placeholder text                     |
| actions          | object   | optional            | Custom action definitions                   |
| local_services   | object   | optional            | Searchable local service links              |
| included_regex   | string[] | `["."]`             | Regexes that an entity match must match first |
| excluded_regex   | string[] | optional            | Regexes removed after include filtering     |

### Example Configuration

```yaml
type: custom:search-card
max_results: 10
search_text: "Search entities..."
excluded_regex:
  - battery
```

### Regex Filtering Example

Include only lights and switches:

```yaml
type: custom:search-card
included_regex:
  - "^light\\."
  - "^switch\\."
```

### Custom Actions Example

```yaml
type: custom:search-card
actions:
  - matches: '^toggle (.+\..+)'
    name: "Toggle {1}"
    service: homeassistant.toggle
    service_data:
      entity_id: { 1 }
```

### Local Services Example

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
```

## Issues and Troubleshooting

If you encounter issues:

1. Clear browser cache
2. Restart Home Assistant
3. Verify card-tools is properly installed
4. Check your configuration syntax

If you believe you have found an error, please create an issue with:

- Your configuration
- Home Assistant version
- Browser and version
- Error messages (if any)

## Roadmap

Planned features:

- Entity exclusion list
- "Show all" results button
- Additional action types
- More polished UI
