customElements.whenDefined("card-tools").then(() => {
  var ct = customElements.get("card-tools");

  const BUILTIN_ACTIONS = [
    {
      matches: "^((magnet:.*)|(.*.torrent.*))$",
      name: "Add to Transmission",
      icon: "mdi:progress-download",
      service: "transmission.add_torrent",
      service_data: {
        torrent: "{1}",
      },
    },
  ];

  const matchAndReplace = (text, matches) => {
    if (typeof text !== "string") {
      return text;
    }

    for (var i = 0; i < matches.length; i++) {
      text = text.replace("{" + i + "}", matches[i]);
    }
    return text;
  };

  const replaceMatchesInValue = (value, matches) => {
    if (Array.isArray(value)) {
      return value.map((item) => replaceMatchesInValue(item, matches));
    }

    if (value && typeof value === "object") {
      const replacedObject = {};
      for (const key in value) {
        replacedObject[key] = replaceMatchesInValue(value[key], matches);
      }
      return replacedObject;
    }

    return matchAndReplace(value, matches);
  };

  const hasItems = (value) => Array.isArray(value) && value.length > 0;

  class SearchCard extends ct.LitElement {
    static get properties() {
      return {
        config: { type: Object },
        hass: { type: Object },
        _results: { type: Array },
        _activeActions: { type: Array },
        _searchValue: { type: String },
        _lastHass: { type: Object },
      };
    }

    static getStubConfig() {
      return {
        max_results: 10,
        search_text: "Type to search...",
      };
    }

    static getConfigForm() {
      return {
        schema: [
          {
            type: "grid",
            name: "",
            flatten: true,
            schema: [
              {
                name: "search_text",
                selector: {
                  text: {},
                },
              },
              {
                name: "max_results",
                selector: {
                  number: {
                    min: 1,
                    step: 1,
                  },
                },
              },
            ],
          },
          {
            type: "expandable",
            name: "",
            title: "Entity filtering",
            flatten: true,
            schema: [
              {
                name: "included_domains",
                selector: {
                  text: {
                    multiple: true,
                  },
                },
              },
              {
                name: "excluded_domains",
                selector: {
                  text: {
                    multiple: true,
                  },
                },
              },
            ],
          },
          {
            name: "actions",
            selector: {
              object: {
                multiple: true,
                label_field: "name",
                description_field: "service",
                fields: {
                  matches: {
                    label: "Regex match",
                    required: true,
                    selector: {
                      text: {},
                    },
                  },
                  name: {
                    label: "Name",
                    required: true,
                    selector: {
                      text: {},
                    },
                  },
                  icon: {
                    label: "Icon",
                    selector: {
                      icon: {},
                    },
                  },
                  service: {
                    label: "Service",
                    required: true,
                    selector: {
                      text: {},
                    },
                  },
                  service_data: {
                    label: "Service data",
                    selector: {
                      object: {},
                    },
                  },
                },
              },
            },
          },
          {
            name: "local_services",
            selector: {
              object: {
                fields: {
                  services: {
                    label: "Services",
                    selector: {
                      object: {
                        multiple: true,
                        label_field: "name",
                        description_field: "category",
                        fields: {
                          name: {
                            label: "Name",
                            required: true,
                            selector: {
                              text: {},
                            },
                          },
                          url: {
                            label: "URL",
                            required: true,
                            selector: {
                              text: {
                                type: "url",
                              },
                            },
                          },
                          icon: {
                            label: "Icon",
                            selector: {
                              icon: {},
                            },
                          },
                          aliases: {
                            label: "Aliases",
                            selector: {
                              text: {
                                multiple: true,
                              },
                            },
                          },
                          category: {
                            label: "Category",
                            selector: {
                              text: {},
                            },
                          },
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        ],
        computeLabel: (schema) => {
          switch (schema.name) {
            case "search_text":
              return "Search placeholder";
            case "max_results":
              return "Maximum results";
            case "included_domains":
              return "Included domains";
            case "excluded_domains":
              return "Excluded domains";
            case "actions":
              return "Actions";
            case "local_services":
              return "Local services";
            default:
              return undefined;
          }
        },
        computeHelper: (schema) => {
          switch (schema.name) {
            case "included_domains":
              return "Only show entities from these Home Assistant domains.";
            case "excluded_domains":
              return "Hide entities from these Home Assistant domains.";
            case "actions":
              return "Regex-triggered service calls shown before search results.";
            case "local_services":
              return "Searchable links that open local network services in a new tab.";
            default:
              return undefined;
          }
        },
        assertConfig: (config) => {
          if (
            hasItems(config?.included_domains) &&
            hasItems(config?.excluded_domains)
          ) {
            throw new Error(
              "included_domains and excluded_domains cannot be used together."
            );
          }
        },
      };
    }

    constructor() {
      super();
      this._results = [];
      this._activeActions = [];
      this._searchValue = "";
      this._lastHass = null;
      this._debouncedSearch = this._debounce((searchText) => {
        this._performSearch(searchText);
      }, 100);
    }

    shouldUpdate(changedProps) {
      return (
        changedProps.has("config") ||
        changedProps.has("_results") ||
        changedProps.has("_activeActions") ||
        changedProps.has("_searchValue")
      );
    }

    setConfig(config) {
      this.config = config;
      this.max_results = this.config.max_results || 10;
      this.search_text = this.config.search_text || "Type to search...";
      this.actions = BUILTIN_ACTIONS.concat(this.config.actions || []);
      this.local_services = this.config.local_services?.services || [];
      this.included_domains = this.config.included_domains;
      this.excluded_domains = this.config.excluded_domains || [];
    }

    getCardSize() {
      return 4;
    }

    render() {
      const results = this._results.slice(0, this.max_results);
      const rows = results.map((result) => this._createResultRow(result));
      const actions = this._activeActions.map((x) =>
        this._createActionRow(x[0], x[1])
      );

      return ct.LitHtml`
      <ha-card>
        <div id="searchContainer">
          <div id="searchTextFieldContainer">
            <ha-input
              id="searchText"
              .value="${this._searchValue}"
              @input="${this._valueChanged}"
              no-label-float
              type="text"
              autocomplete="off"
              icon
              iconTrailing
              label="${this.search_text}"
            >
              <ha-icon icon="mdi:magnify" id="searchIcon" slot="leadingIcon"></ha-icon>
              <ha-icon-button
                slot="trailingIcon"
                @click="${this._clearInput}"
                alt="Clear"
                title="Clear"
              >
                <ha-icon icon="mdi:close"></ha-icon>
              </ha-icon-button>
            </ha-input>
          </div>

          ${
            results.length > 0
              ? ct.LitHtml`<div id="count">Showing ${results.length} of ${this._results.length} results</div>`
              : ""
          }
        </div>
        ${
          rows.length > 0 || actions.length > 0
            ? ct.LitHtml`<div id="results">${actions}${rows}</div>`
            : ""
        }
      </ha-card>
    `;
    }

    _createResultRow(result) {
      if (result.type === "local_service") {
        return this._createLocalServiceRow(result.service);
      }

      const entity_id = result.entity_id;
      var row = ct.createEntityRow({ entity: entity_id });
      row.addEventListener("click", () => ct.moreInfo(entity_id));
      row.hass = this.hass;
      return row;
    }

    _createLocalServiceRow(service) {
      const secondaryText = service.category || service.url;
      const icon = service.icon || "mdi:server-network";

      return ct.LitHtml`
        <div
          class="local-service-row"
          role="link"
          tabindex="0"
          @click=${() => this._openLocalService(service.url)}
          @keydown=${(ev) => this._handleLocalServiceKeydown(ev, service.url)}
        >
          <ha-icon class="local-service-icon" .icon=${icon}></ha-icon>
          <div class="local-service-text">
            <div class="local-service-name">${service.name}</div>
            ${
              secondaryText
                ? ct.LitHtml`<div class="local-service-secondary">${secondaryText}</div>`
                : ""
            }
          </div>
        </div>
      `;
    }

    _createActionRow(action, matches) {
      var service_data = replaceMatchesInValue(
        action.service_data || {},
        matches
      );

      const elem = cardTools.createThing("service-row", {
        type: "call",
        name: matchAndReplace(action.name, matches),
        icon: action.icon || "mdi:lamp",
        service: action.service,
        service_data: service_data,
      });
      elem.hass = this.hass;
      return elem;
    }

    _valueChanged(ev) {
      this._searchValue = ev.target.value;
      this._debouncedSearch(this._searchValue);
    }

    _clearInput() {
      this._searchValue = "";
      this._results = [];
      this._activeActions = [];
    }

    _openLocalService(url) {
      const newWindow = window.open(url, "_blank", "noopener");
      if (newWindow) {
        newWindow.opener = null;
      }
    }

    _handleLocalServiceKeydown(ev, url) {
      if (ev.key === "Enter" || ev.key === " ") {
        ev.preventDefault();
        this._openLocalService(url);
      }
    }

    _debounce(func, wait) {
      let timeout;
      return function executedFunction(...args) {
        const later = () => {
          clearTimeout(timeout);
          func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
      };
    }

    _performSearch(searchText) {
      if (!this.config || !this.hass || searchText === "") {
        this._results = [];
        this._activeActions = [];
        return;
      }

      try {
        const searchRegex = new RegExp(searchText, "i");
        const localServiceResults = this._getLocalServiceResults(searchRegex);
        const entityResults = this._getEntityResults(searchRegex);

        this._results = localServiceResults.concat(entityResults);
        this._activeActions = this._getActivatedActions(searchText);
      } catch (err) {
        console.warn(err);
        this._results = [];
        this._activeActions = [];
      }
    }

    _getEntityResults(searchRegex) {
      const results = [];

      for (const entity_id in this.hass.states) {
        if (
          (entity_id.search(searchRegex) >= 0 ||
            this.hass.states[entity_id].attributes.friendly_name?.search(
              searchRegex
            ) >= 0) &&
          (this.included_domains
            ? this.included_domains.includes(entity_id.split(".")[0])
            : !this.excluded_domains.includes(entity_id.split(".")[0]))
        ) {
          results.push({
            type: "entity",
            entity_id: entity_id,
          });
        }
      }

      return results.sort((a, b) => a.entity_id.localeCompare(b.entity_id));
    }

    _getLocalServiceResults(searchRegex) {
      return this.local_services
        .filter((service) => this._localServiceMatches(searchRegex, service))
        .map((service) => ({
          type: "local_service",
          service: service,
        }));
    }

    _localServiceMatches(searchRegex, service) {
      if (!service || !service.name || !service.url) {
        return false;
      }

      const searchableParts = [service.name, service.category].concat(
        service.aliases || []
      );

      return searchableParts.some(
        (part) => typeof part === "string" && part.search(searchRegex) >= 0
      );
    }

    _getActivatedActions(searchText) {
      var active = [];

      for (const action of this.actions) {
        if (this._serviceExists(action.service)) {
          var matches = searchText.match(action.matches);
          if (matches != null) {
            active.push([action, matches]);
          }
        }
      }
      return active;
    }

    _serviceExists(serviceCall) {
      var [domain, service] = serviceCall.split(".");
      var servicesForDomain = this.hass.services[domain];
      return servicesForDomain && service in servicesForDomain;
    }

    static get styles() {
      return ct.LitCSS`
      #searchContainer {
        width: 90%;
        display: block;
        margin-left: auto;
        margin-right: auto;
      }
      #searchTextFieldContainer {
        display: flex;
        padding-top: 5px;
        padding-bottom: 5px;
      }
      #searchText {
        flex-grow: 1;
      }
      #count {
        text-align: right;
        font-style: italic;
      }
      #results {
        width: 90%;
        display: block;
        padding-bottom: 15px;
        margin-top: 15px;
        margin-left: auto;
        margin-right: auto;
      }
      .local-service-row {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 12px 16px;
        border-radius: 12px;
        cursor: pointer;
        transition: background-color 0.2s ease;
      }
      .local-service-row:hover,
      .local-service-row:focus {
        background: rgba(var(--rgb-primary-text-color, 0, 0, 0), 0.08);
        outline: none;
      }
      .local-service-icon {
        color: var(--primary-color);
        flex-shrink: 0;
      }
      .local-service-text {
        min-width: 0;
      }
      .local-service-name {
        color: var(--primary-text-color);
      }
      .local-service-secondary {
        color: var(--secondary-text-color);
        font-size: 0.9em;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    `;
    }
  }

  customElements.define("search-card", SearchCard);
});

setTimeout(() => {
  if (customElements.get("card-tools")) return;
  customElements.define(
    "search-card",
    class extends HTMLElement {
      setConfig() {
        throw new Error(
          "Can't find card-tools. See https://github.com/thomasloven/lovelace-card-tools"
        );
      }
    }
  );
}, 2000);

window.customCards = window.customCards || [];
window.customCards.push({
  type: "search-card",
  name: "Search Card",
  preview: true,
  description: "Card to search entities and local services",
  documentationURL: "https://github.com/postlund/search-card",
});
