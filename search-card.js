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
  const cloneValue = (value) => JSON.parse(JSON.stringify(value));
  const parseListInput = (value) =>
    value
      .split(/\n|,/)
      .map((item) => item.trim())
      .filter(Boolean);
  const stringifyListInput = (value) =>
    Array.isArray(value) ? value.join("\n") : "";
  const normalizeList = (value, fallback = []) => {
    if (value === undefined) {
      return fallback;
    }

    if (Array.isArray(value)) {
      return value;
    }

    return [value];
  };

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

    static getConfigElement() {
      return document.createElement("search-card-editor");
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
      this.included_regex = normalizeList(this.config.included_regex, ["."]);
      this.excluded_regex = normalizeList(this.config.excluded_regex, []);
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
              type="search"
              autocomplete="off"
              with-clear
              placeholder="${this.search_text}"
            >
              <ha-icon icon="mdi:magnify" id="searchIcon" slot="start"></ha-icon>
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
        const includedRegexes = this._compileRegexList(this.included_regex);
        const excludedRegexes = this._compileRegexList(this.excluded_regex);
        const localServiceResults = this._getLocalServiceResults(searchRegex);
        const entityResults = this._getEntityResults(
          searchRegex,
          includedRegexes,
          excludedRegexes
        );

        this._results = localServiceResults.concat(entityResults);
        this._activeActions = this._getActivatedActions(searchText);
      } catch (err) {
        console.warn(err);
        this._results = [];
        this._activeActions = [];
      }
    }

    _compileRegexList(patterns) {
      return patterns.map((pattern) => new RegExp(pattern, "i"));
    }

    _getEntityResults(searchRegex, includedRegexes, excludedRegexes) {
      const results = [];

      for (const entity_id in this.hass.states) {
        const state = this.hass.states[entity_id];
        const searchableFields = [
          entity_id,
          state.attributes.friendly_name,
        ].filter((field) => typeof field === "string");

        if (
          this._matchesAnyRegex(searchableFields, [searchRegex]) &&
          this._matchesAnyRegex(searchableFields, includedRegexes) &&
          !this._matchesAnyRegex(searchableFields, excludedRegexes)
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

    _matchesAnyRegex(values, regexes) {
      if (!hasItems(regexes)) {
        return false;
      }

      return values.some((value) =>
        regexes.some((regex) => regex.test(value))
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

  class SearchCardEditor extends ct.LitElement {
    static get properties() {
      return {
        hass: { type: Object },
        _config: { type: Object },
        _dialog: { type: Object },
      };
    }

    constructor() {
      super();
      this._config = SearchCard.getStubConfig();
      this._dialog = null;
    }

    setConfig(config) {
      this._config = {
        ...SearchCard.getStubConfig(),
        ...cloneValue(config || {}),
      };
      this._dialog = null;
    }

    render() {
      const config = this._config || SearchCard.getStubConfig();
      const localServices = config.local_services?.services || [];
      const customActions = config.actions || [];
      const validationError = this._getValidationError();

      return ct.LitHtml`
        <div class="editor">
          <div class="field-grid">
            <label class="field">
              <span class="label">Search placeholder</span>
              <input
                type="text"
                .value=${config.search_text || ""}
                @input=${(ev) =>
                  this._updateConfigField("search_text", ev.target.value)}
              />
            </label>
            <label class="field">
              <span class="label">Maximum results</span>
              <input
                type="number"
                min="1"
                step="1"
                .value=${String(config.max_results || 10)}
                @input=${(ev) => this._updateNumberField("max_results", ev)}
              />
            </label>
          </div>

          <div class="field">
            <span class="label">Included regex</span>
            <textarea
              rows="3"
              .value=${stringifyListInput(config.included_regex)}
              @input=${(ev) => this._updateListField("included_regex", ev)}
            ></textarea>
            <div class="helper">
              One regex per line or comma-separated. Defaults to a single "."
              when left empty.
            </div>
          </div>

          <div class="field">
            <span class="label">Excluded regex</span>
            <textarea
              rows="3"
              .value=${stringifyListInput(config.excluded_regex)}
              @input=${(ev) => this._updateListField("excluded_regex", ev)}
            ></textarea>
            <div class="helper">
              Remove entity matches that match any of these regexes.
            </div>
          </div>

          ${
            validationError
              ? ct.LitHtml`<div class="error">${validationError}</div>`
              : ""
          }

          <div class="section">
            <div class="section-header">
              <div>
                <div class="section-title">Actions</div>
                <div class="helper">
                  Regex-triggered service calls shown before search results.
                </div>
              </div>
              <button type="button" @click=${() => this._openActionDialog()}>
                Add action
              </button>
            </div>
            ${
              customActions.length > 0
                ? customActions.map((action, index) =>
                    this._renderListRow({
                      title: action.name || `Action ${index + 1}`,
                      secondary: `${action.service || ""}${
                        action.matches ? ` | ${action.matches}` : ""
                      }`,
                      onEdit: () => this._openActionDialog(index),
                      onDelete: () => this._removeAction(index),
                    })
                  )
                : ct.LitHtml`<div class="empty">No custom actions configured.</div>`
            }
          </div>

          <div class="section">
            <div class="section-header">
              <div>
                <div class="section-title">Local services</div>
                <div class="helper">
                  Searchable links that open local network services in a new
                  tab.
                </div>
              </div>
              <button
                type="button"
                @click=${() => this._openLocalServiceDialog()}
              >
                Add local service
              </button>
            </div>
            ${
              localServices.length > 0
                ? localServices.map((service, index) =>
                    this._renderListRow({
                      title: service.name || `Service ${index + 1}`,
                      secondary: service.category
                        ? `${service.category} | ${service.url}`
                        : service.url,
                      onEdit: () => this._openLocalServiceDialog(index),
                      onDelete: () => this._removeLocalService(index),
                    })
                  )
                : ct.LitHtml`<div class="empty">No local services configured.</div>`
            }
          </div>

          ${this._dialog ? this._renderDialog() : ""}
        </div>
      `;
    }

    _renderListRow({ title, secondary, onEdit, onDelete }) {
      return ct.LitHtml`
        <div class="list-row">
          <div class="list-row-body">
            <div class="list-row-title">${title}</div>
            <div class="list-row-secondary">${secondary}</div>
          </div>
          <div class="list-row-actions">
            <button type="button" class="secondary-button" @click=${onEdit}>
              Edit
            </button>
            <button type="button" class="secondary-button" @click=${onDelete}>
              Remove
            </button>
          </div>
        </div>
      `;
    }

    _renderDialog() {
      const dialog = this._dialog;

      return ct.LitHtml`
        <div class="dialog-backdrop" @click=${this._closeDialog}>
          <div class="dialog" @click=${this._stopPropagation}>
            <div class="dialog-title">${dialog.title}</div>
            ${dialog.error ? ct.LitHtml`<div class="error">${dialog.error}</div>` : ""}
            ${
              dialog.type === "action"
                ? this._renderActionDialog(dialog.value)
                : this._renderLocalServiceDialog(dialog.value)
            }
            <div class="dialog-actions">
              <button type="button" class="secondary-button" @click=${this._closeDialog}>
                Cancel
              </button>
              <button type="button" @click=${this._saveDialog}>Save</button>
            </div>
          </div>
        </div>
      `;
    }

    _renderActionDialog(value) {
      return ct.LitHtml`
        <label class="field">
          <span class="label">Name</span>
          <input
            type="text"
            .value=${value.name}
            @input=${(ev) => this._updateDialogValue("name", ev.target.value)}
          />
        </label>
        <label class="field">
          <span class="label">Regex match</span>
          <input
            type="text"
            .value=${value.matches}
            @input=${(ev) =>
              this._updateDialogValue("matches", ev.target.value)}
          />
        </label>
        <label class="field">
          <span class="label">Service</span>
          <input
            type="text"
            .value=${value.service}
            @input=${(ev) =>
              this._updateDialogValue("service", ev.target.value)}
          />
        </label>
        <label class="field">
          <span class="label">Icon</span>
          <input
            type="text"
            .value=${value.icon}
            @input=${(ev) => this._updateDialogValue("icon", ev.target.value)}
          />
        </label>
        <label class="field">
          <span class="label">Service data (JSON object)</span>
          <textarea
            rows="6"
            .value=${value.service_data_text}
            @input=${(ev) =>
              this._updateDialogValue("service_data_text", ev.target.value)}
          ></textarea>
        </label>
      `;
    }

    _renderLocalServiceDialog(value) {
      return ct.LitHtml`
        <label class="field">
          <span class="label">Name</span>
          <input
            type="text"
            .value=${value.name}
            @input=${(ev) => this._updateDialogValue("name", ev.target.value)}
          />
        </label>
        <label class="field">
          <span class="label">URL</span>
          <input
            type="url"
            .value=${value.url}
            @input=${(ev) => this._updateDialogValue("url", ev.target.value)}
          />
        </label>
        <label class="field">
          <span class="label">Icon</span>
          <input
            type="text"
            .value=${value.icon}
            @input=${(ev) => this._updateDialogValue("icon", ev.target.value)}
          />
        </label>
        <label class="field">
          <span class="label">Aliases</span>
          <input
            type="text"
            .value=${value.aliases_text}
            @input=${(ev) =>
              this._updateDialogValue("aliases_text", ev.target.value)}
          />
          <div class="helper">
            Separate aliases with commas or new lines.
          </div>
        </label>
        <label class="field">
          <span class="label">Category</span>
          <input
            type="text"
            .value=${value.category}
            @input=${(ev) =>
              this._updateDialogValue("category", ev.target.value)}
          />
        </label>
      `;
    }

    _getValidationError() {
      return "";
    }

    _updateConfigField(field, value) {
      this._emitConfig({
        ...this._config,
        [field]: value,
      });
    }

    _updateNumberField(field, ev) {
      const value = Number.parseInt(ev.target.value, 10);
      this._emitConfig({
        ...this._config,
        [field]: Number.isFinite(value) && value > 0 ? value : 10,
      });
    }

    _updateListField(field, ev) {
      const listValue = parseListInput(ev.target.value);
      const config = { ...this._config };

      if (listValue.length > 0) {
        config[field] = listValue;
      } else {
        delete config[field];
      }

      this._emitConfig(config);
    }

    _emitConfig(config) {
      this._config = config;
      const event = new Event("config-changed", {
        bubbles: true,
        composed: true,
      });
      event.detail = { config: this._config };
      this.dispatchEvent(event);
    }

    _openActionDialog(index = null) {
      const action = index === null ? {} : cloneValue(this._config.actions[index]);
      this._dialog = {
        type: "action",
        index: index,
        title: index === null ? "Add action" : "Edit action",
        error: "",
        value: {
          name: action.name || "",
          matches: action.matches || "",
          service: action.service || "",
          icon: action.icon || "",
          service_data_text: action.service_data
            ? JSON.stringify(action.service_data, null, 2)
            : "",
        },
      };
    }

    _openLocalServiceDialog(index = null) {
      const service =
        index === null
          ? {}
          : cloneValue(this._config.local_services?.services[index] || {});
      this._dialog = {
        type: "local_service",
        index: index,
        title: index === null ? "Add local service" : "Edit local service",
        error: "",
        value: {
          name: service.name || "",
          url: service.url || "",
          icon: service.icon || "",
          aliases_text: stringifyListInput(service.aliases),
          category: service.category || "",
        },
      };
    }

    _updateDialogValue(field, value) {
      this._dialog = {
        ...this._dialog,
        error: "",
        value: {
          ...this._dialog.value,
          [field]: value,
        },
      };
    }

    _saveDialog = () => {
      if (this._dialog.type === "action") {
        this._saveActionDialog();
        return;
      }

      this._saveLocalServiceDialog();
    };

    _saveActionDialog() {
      const value = this._dialog.value;

      if (!value.name.trim() || !value.matches.trim() || !value.service.trim()) {
        this._setDialogError("Name, regex match, and service are required.");
        return;
      }

      let serviceData;
      if (value.service_data_text.trim() !== "") {
        try {
          serviceData = JSON.parse(value.service_data_text);
        } catch (err) {
          this._setDialogError("Service data must be valid JSON.");
          return;
        }

        if (
          !serviceData ||
          Array.isArray(serviceData) ||
          typeof serviceData !== "object"
        ) {
          this._setDialogError("Service data must be a JSON object.");
          return;
        }
      }

      const nextAction = {
        name: value.name.trim(),
        matches: value.matches.trim(),
        service: value.service.trim(),
      };

      if (value.icon.trim()) {
        nextAction.icon = value.icon.trim();
      }

      if (serviceData) {
        nextAction.service_data = serviceData;
      }

      const actions = cloneValue(this._config.actions || []);
      if (this._dialog.index === null) {
        actions.push(nextAction);
      } else {
        actions[this._dialog.index] = nextAction;
      }

      this._emitConfig({
        ...this._config,
        actions: actions,
      });
      this._dialog = null;
    }

    _saveLocalServiceDialog() {
      const value = this._dialog.value;

      if (!value.name.trim() || !value.url.trim()) {
        this._setDialogError("Name and URL are required.");
        return;
      }

      const nextService = {
        name: value.name.trim(),
        url: value.url.trim(),
      };

      if (value.icon.trim()) {
        nextService.icon = value.icon.trim();
      }

      const aliases = parseListInput(value.aliases_text);
      if (aliases.length > 0) {
        nextService.aliases = aliases;
      }

      if (value.category.trim()) {
        nextService.category = value.category.trim();
      }

      const services = cloneValue(this._config.local_services?.services || []);
      if (this._dialog.index === null) {
        services.push(nextService);
      } else {
        services[this._dialog.index] = nextService;
      }

      this._emitConfig({
        ...this._config,
        local_services: {
          services: services,
        },
      });
      this._dialog = null;
    }

    _removeAction(index) {
      const actions = cloneValue(this._config.actions || []);
      actions.splice(index, 1);
      const config = { ...this._config };

      if (actions.length > 0) {
        config.actions = actions;
      } else {
        delete config.actions;
      }

      this._emitConfig(config);
    }

    _removeLocalService(index) {
      const services = cloneValue(this._config.local_services?.services || []);
      services.splice(index, 1);
      const config = { ...this._config };

      if (services.length > 0) {
        config.local_services = { services: services };
      } else {
        delete config.local_services;
      }

      this._emitConfig(config);
    }

    _setDialogError(message) {
      this._dialog = {
        ...this._dialog,
        error: message,
      };
    }

    _closeDialog = () => {
      this._dialog = null;
    };

    _stopPropagation(ev) {
      ev.stopPropagation();
    }

    static get styles() {
      return ct.LitCSS`
        .editor {
          display: flex;
          flex-direction: column;
          gap: 16px;
          padding: 8px 0 16px;
        }
        .field-grid {
          display: grid;
          gap: 12px;
          grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
        }
        .field {
          display: flex;
          flex-direction: column;
          gap: 6px;
        }
        .label,
        .section-title,
        .dialog-title {
          color: var(--primary-text-color);
          font-weight: 600;
        }
        .helper,
        .list-row-secondary {
          color: var(--secondary-text-color);
          font-size: 0.9em;
        }
        input,
        textarea {
          box-sizing: border-box;
          width: 100%;
          padding: 10px 12px;
          border: 1px solid var(--divider-color);
          border-radius: 10px;
          background: var(--card-background-color);
          color: var(--primary-text-color);
          font: inherit;
        }
        textarea {
          resize: vertical;
        }
        button {
          border: none;
          border-radius: 999px;
          padding: 10px 16px;
          background: var(--primary-color);
          color: var(--text-primary-color, #fff);
          cursor: pointer;
          font: inherit;
        }
        .secondary-button {
          background: rgba(var(--rgb-primary-text-color, 0, 0, 0), 0.08);
          color: var(--primary-text-color);
        }
        .section {
          display: flex;
          flex-direction: column;
          gap: 12px;
          padding-top: 8px;
          border-top: 1px solid var(--divider-color);
        }
        .section-header {
          display: flex;
          align-items: flex-start;
          justify-content: space-between;
          gap: 12px;
        }
        .list-row {
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 12px;
          padding: 12px;
          border: 1px solid var(--divider-color);
          border-radius: 12px;
        }
        .list-row-body {
          min-width: 0;
        }
        .list-row-title {
          color: var(--primary-text-color);
          font-weight: 500;
        }
        .list-row-secondary {
          overflow-wrap: anywhere;
        }
        .list-row-actions {
          display: flex;
          gap: 8px;
          flex-wrap: wrap;
          justify-content: flex-end;
        }
        .empty,
        .error {
          color: var(--secondary-text-color);
        }
        .error {
          color: var(--error-color);
        }
        .dialog-backdrop {
          position: fixed;
          inset: 0;
          z-index: 10;
          display: flex;
          align-items: center;
          justify-content: center;
          padding: 16px;
          background: rgba(0, 0, 0, 0.45);
        }
        .dialog {
          width: min(560px, 100%);
          max-height: calc(100vh - 32px);
          overflow: auto;
          display: flex;
          flex-direction: column;
          gap: 14px;
          padding: 20px;
          border-radius: 16px;
          background: var(--card-background-color);
          box-shadow: var(--ha-card-box-shadow, 0 4px 12px rgba(0, 0, 0, 0.2));
        }
        .dialog-actions {
          display: flex;
          justify-content: flex-end;
          gap: 8px;
        }
      `;
    }
  }

  customElements.define("search-card-editor", SearchCardEditor);
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
