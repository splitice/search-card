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
  const SEARCH_PRIORITY_LABEL = "search_priority";
  const SEARCH_HIDDEN_LABEL = "search_hidden";
  const PANEL_NAMES = {
    energy: "Energy", history: "History", logbook: "Activity", map: "Map",
    calendar: "Calendar", config: "Settings", developer_tools: "Developer tools",
    lovelace: "Overview", home: "Home", todo: "To-do lists", media_browser: "Media",
    shopping_list: "Shopping list", hassio: "Settings",
  };
  const PANEL_ICONS = {
    energy: "mdi:lightning-bolt", history: "mdi:chart-box", logbook: "mdi:format-list-bulleted",
    map: "mdi:map", calendar: "mdi:calendar", config: "mdi:cog", developer_tools: "mdi:hammer",
    lovelace: "mdi:view-dashboard", home: "mdi:home", todo: "mdi:clipboard-list",
    media_browser: "mdi:play-box-multiple",
  };
  const initialResultLimit = (value) => Number.isFinite(value) && value >= 1 ? Math.floor(value) : 10;
  const isNavigationPath = (path) => /^\/[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*$/.test(path);
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
        _dropdownOpen: { type: Boolean },
        _visibleResultCount: { type: Number },
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
      this._dropdownOpen = false;
      this._visibleResultCount = 100;
      this._hass = null;
      this._searchPriorityEntityIds = new Set();
      this._searchHiddenEntityIds = new Set();
      this._registryMetadataRequest = null;
      this._debouncedSearch = this._debounce((searchText) => {
        this._performSearch(searchText);
      }, 100);
    }

    get hass() {
      return this._hass;
    }

    set hass(value) {
      const oldHass = this._hass;
      this._hass = value;
      this.requestUpdate("hass", oldHass);

      if (value?.connection !== oldHass?.connection) {
        this._searchPriorityEntityIds = new Set();
        this._searchHiddenEntityIds = new Set();
        this._registryMetadataRequest = null;
        this._refreshSearchLabelEntityIds();
      }
      if (value?.panels !== oldHass?.panels && this._searchValue) {
        this._performSearch(this._searchValue);
      }
    }

    shouldUpdate(changedProps) {
      return (
        changedProps.has("config") ||
        changedProps.has("_results") ||
        changedProps.has("_activeActions") ||
        changedProps.has("_searchValue") ||
        changedProps.has("_dropdownOpen") ||
        changedProps.has("_visibleResultCount")
      );
    }

    setConfig(config) {
      this.config = config;
      this.max_results = initialResultLimit(config.max_results);
      this.search_text = this.config.search_text || "Type to search...";
      this.actions = BUILTIN_ACTIONS.concat(this.config.actions || []);
      this.local_services = this.config.local_services?.services || [];
      this.included_regex = normalizeList(this.config.included_regex, ["."]);
      this.excluded_regex = normalizeList(this.config.excluded_regex, []);
    }

    getCardSize() {
      return 1;
    }

    render() {
      const results = this._results.slice(0, this._visibleResultCount);
      const expanded = this._dropdownOpen && (results.length > 0 || this._activeActions.length > 0);
      return ct.LitHtml`
        <ha-card>
          <div id="searchContainer">
            <div id="searchTextFieldContainer">
              <ha-input id="searchText" .value=${this._searchValue}
                @input=${this._valueChanged} @focusin=${this._openDropdown} @click=${this._openDropdown}
                @keydown=${this._searchKeydown}
                aria-controls="results" aria-expanded=${String(expanded)}
                type="search" autocomplete="off" with-clear placeholder=${this.search_text}>
                <ha-icon icon="mdi:magnify" id="searchIcon" slot="start"></ha-icon>
              </ha-input>
            </div>
          </div>
        </ha-card>
        <div id="results" popover="manual" ?hidden=${!expanded}
          role="region" aria-label="Search results" @scroll=${this._loadMoreResults}>
          <div id="count" role="status">${this._results.length} results</div>
          ${this._activeActions.map((x) => this._createActionRow(x[0], x[1]))}
          ${results.map((result) => this._createResultRow(result))}
          ${results.length < this._results.length
            ? ct.LitHtml`<button class="load-more" @click=${this._showMoreResults}>More results</button>` : ""}
        </div>
      `;
    }

    connectedCallback() {
      super.connectedCallback();
      this._dropdownListeners = new AbortController();
      const signal = this._dropdownListeners.signal;
      const outside = (event) => {
        if (!event.composedPath().includes(this)) this._closeDropdown();
      };
      document.addEventListener("pointerdown", outside, { capture: true, signal });
      document.addEventListener("focusin", outside, { signal });
      this.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && this._dropdownOpen) {
          event.preventDefault();
          event.stopPropagation();
          this.renderRoot.querySelector("#searchText")?.focus();
          this._closeDropdown();
        }
      }, { signal });
      window.addEventListener("scroll", () => this._queueDropdownPosition(), { capture: true, passive: true, signal });
      window.addEventListener("resize", () => this._queueDropdownPosition(true), { signal });
      window.visualViewport?.addEventListener("resize", () => this._queueDropdownPosition(true), { signal });
      window.visualViewport?.addEventListener("scroll", () => this._queueDropdownPosition(), { signal });
      this._inputResize = new ResizeObserver(() => this._queueDropdownPosition(true));
      this.updateComplete.then(() => {
        if (this.isConnected) this._inputResize?.observe(this.renderRoot.querySelector("#searchText"));
      });
    }

    disconnectedCallback() {
      this._dropdownListeners?.abort();
      this._inputResize?.disconnect();
      cancelAnimationFrame(this._positionFrame);
      this._positionFrame = null;
      this._debouncedSearch.cancel();
      this._closeDropdown();
      super.disconnectedCallback();
    }

    updated(changed) {
      const dropdown = this.renderRoot.querySelector("#results");
      if (!dropdown || dropdown.hidden) {
        if (dropdown?.matches(":popover-open")) dropdown.hidePopover();
        return;
      }
      const opening = dropdown.showPopover && !dropdown.matches(":popover-open");
      if (opening) {
        dropdown.style.visibility = "hidden";
        dropdown.showPopover();
      }
      if (changed.has("_searchValue")) dropdown.scrollTop = 0;
      this._queueDropdownPosition(opening || changed.has("config") || changed.has("_searchValue") || changed.has("_dropdownOpen"));
    }

    _openDropdown() { this._dropdownOpen = true; }

    _closeDropdown() {
      this._dropdownOpen = false;
      // Close immediately, including when the dashboard disconnects the card.
      const dropdown = this.renderRoot?.querySelector("#results");
      if (dropdown) {
        if (dropdown.matches(":popover-open")) dropdown.hidePopover();
        dropdown.hidden = true;
      }
    }

    _searchKeydown(event) {
      if (event.key === "ArrowDown") {
        event.preventDefault();
        this._openDropdown();
        this.updateComplete.then(() => {
          this.renderRoot.querySelector('#results [tabindex="0"], #results button')?.focus();
        });
      }
    }

    _showMoreResults() {
      this._visibleResultCount = Math.min(this._results.length, this._visibleResultCount + 100);
    }

    _loadMoreResults(event) {
      const list = event.currentTarget;
      if (list.scrollTop + list.clientHeight >= list.scrollHeight - 160) this._showMoreResults();
    }

    _queueDropdownPosition(ensureRoom = false) {
      if (!this._dropdownOpen) return;
      this._ensureDropdownRoom ||= ensureRoom;
      if (this._positionFrame != null) return;
      this._positionFrame = requestAnimationFrame(() => {
        this._positionFrame = null;
        const scroll = this._ensureDropdownRoom;
        this._ensureDropdownRoom = false;
        this._positionDropdown(scroll);
      });
    }

    _positionDropdown(ensureRoom) {
      const input = this.renderRoot.querySelector("#searchText");
      const dropdown = this.renderRoot.querySelector("#results");
      if (!input || !dropdown || dropdown.hidden) return;
      const viewport = window.visualViewport;
      const top = (viewport?.offsetTop || 0) + 8;
      const left = (viewport?.offsetLeft || 0) + 8;
      const bottom = top + (viewport?.height || window.innerHeight) - 16;
      const right = left + (viewport?.width || window.innerWidth) - 16;
      let rect = input.getBoundingClientRect();
      // max_results sizes the popup, never the result set or the paging batch.
      // Entity rows are approximately 56px; count and popup padding are additional.
      const countHeight = dropdown.querySelector("#count")?.getBoundingClientRect().height || 34;
      const initialHeight = this.max_results * 56 + countHeight + 10;
      const desired = Math.min(initialHeight, dropdown.scrollHeight, Math.max(0, bottom - top - rect.height - 4));
      if (ensureRoom && rect.bottom + 4 + desired > bottom) {
        // HA dashboards can scroll inside several shadow roots. Move only as far as
        // needed, starting with the nearest scroll container, without resizing the card.
        let ancestor = input;
        while (ancestor) {
          ancestor = ancestor.parentElement || ancestor.getRootNode().host;
          if (!ancestor) break;
          if (ancestor === document.scrollingElement || /auto|scroll/.test(getComputedStyle(ancestor).overflowY)) {
            const remaining = input.getBoundingClientRect().bottom + 4 + desired - bottom;
            if (remaining <= 0) break;
            ancestor.scrollTop += remaining;
          }
        }
        rect = input.getBoundingClientRect();
      }
      if (rect.bottom < top || rect.top > bottom) {
        this._closeDropdown();
        return;
      }
      const below = Math.max(0, bottom - rect.bottom - 4);
      const above = Math.max(0, rect.top - top - 4);
      // At the end of a non-scrollable page, a select-style popup may need to flip.
      const upward = below < Math.min(desired, 120) && above > below;
      const height = Math.min(desired, upward ? above : below);
      const width = Math.min(rect.width, right - left);
      Object.assign(dropdown.style, {
        left: `${Math.max(left, Math.min(rect.left, right - width))}px`,
        top: `${upward ? rect.top - height - 4 : Math.max(top, rect.bottom + 4)}px`,
        width: `${width}px`, maxHeight: `${height}px`, visibility: "visible",
      });
    }

    _createResultRow(result) {
      if (result.type === "navigation") {
        return this._createLocalServiceRow({ ...result.panel, url: result.panel.path, category: "Home Assistant" }, true);
      }
      if (result.type === "local_service") {
        return this._createLocalServiceRow(result.service);
      }

      const entity_id = result.entity_id;
      var row = ct.createEntityRow({ entity: entity_id });
      row.tabIndex = 0;
      const open = () => { this._closeDropdown(); ct.moreInfo(entity_id); };
      row.addEventListener("click", open);
      row.addEventListener("keydown", (event) => {
        if ((event.key === "Enter" || event.key === " ") && event.composedPath()[0] === row) {
          event.preventDefault();
          open();
        }
      });
      row.hass = this.hass;
      return row;
    }

    _createLocalServiceRow(service, navigation = false) {
      const secondaryText = service.category || service.url;
      const icon = service.icon || "mdi:server-network";

      return ct.LitHtml`
        <div
          class="local-service-row"
          role="link"
          tabindex="0"
          @click=${() => navigation ? this._openNavigation(service.url) : this._openLocalService(service.url)}
          @keydown=${(ev) => this._handleLocalServiceKeydown(ev, service.url, navigation)}
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
      this._visibleResultCount = 100;
      this._dropdownOpen = true;
      this._debouncedSearch(this._searchValue);
    }

    _clearInput() {
      this._debouncedSearch.cancel();
      this._closeDropdown();
      this._visibleResultCount = 100;
      this._searchValue = "";
      this._results = [];
      this._activeActions = [];
    }

    _openLocalService(url) {
      this._closeDropdown();
      const newWindow = window.open(url, "_blank", "noopener");
      if (newWindow) {
        newWindow.opener = null;
      }
    }

    _openNavigation(path) {
      if (!isNavigationPath(path)) return;
      this._closeDropdown();
      const from = window.location.pathname + window.location.search + window.location.hash;
      window.history.pushState({ from }, "", path);
      window.dispatchEvent(new CustomEvent("location-changed", { detail: { replace: false } }));
    }

    _handleLocalServiceKeydown(ev, url, navigation = false) {
      if (ev.key === "Enter" || ev.key === " ") {
        ev.preventDefault();
        if (navigation) this._openNavigation(url);
        else this._openLocalService(url);
      }
    }

    _debounce(func, wait) {
      let timeout;
      const debounced = function executedFunction(...args) {
        const later = () => {
          clearTimeout(timeout);
          func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
      };
      debounced.cancel = () => clearTimeout(timeout);
      return debounced;
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

        this._results = this._sortResults(
          localServiceResults.concat(this._getNavigationResults(searchRegex), entityResults)
        );
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

    async _refreshSearchLabelEntityIds() {
      const connection = this.hass?.connection;
      if (!connection) {
        this._searchPriorityEntityIds = new Set();
        this._searchHiddenEntityIds = new Set();
        return;
      }

      const requestToken = {};
      this._registryMetadataRequest = requestToken;

      try {
        const [labels, entityRegistryDisplay] = await Promise.all([
          connection.sendMessagePromise({
            type: "config/label_registry/list",
          }),
          connection.sendMessagePromise({
            type: "config/entity_registry/list_for_display",
          }),
        ]);

        if (this._registryMetadataRequest !== requestToken) {
          return;
        }

        const labelIdsByName = new Map(
          (Array.isArray(labels) ? labels : [])
            .filter((label) => typeof label?.name === "string")
            .map((label) => [label.name.trim().toLowerCase(), label.label_id])
        );
        const searchPriorityLabelId = labelIdsByName.get(SEARCH_PRIORITY_LABEL);
        const searchHiddenLabelId = labelIdsByName.get(SEARCH_HIDDEN_LABEL);
        const searchPriorityEntityIds = new Set();
        const searchHiddenEntityIds = new Set();

        for (const entry of entityRegistryDisplay?.entities || []) {
          if (typeof entry?.ei !== "string" || !Array.isArray(entry.lb)) {
            continue;
          }

          if (searchPriorityLabelId && entry.lb.includes(searchPriorityLabelId)) {
            searchPriorityEntityIds.add(entry.ei);
          }

          if (searchHiddenLabelId && entry.lb.includes(searchHiddenLabelId)) {
            searchHiddenEntityIds.add(entry.ei);
          }
        }

        this._searchPriorityEntityIds = searchPriorityEntityIds;
        this._searchHiddenEntityIds = searchHiddenEntityIds;
        if (this._searchValue) {
          this._performSearch(this._searchValue);
        }
      } catch (err) {
        if (this._registryMetadataRequest === requestToken) {
          this._searchPriorityEntityIds = new Set();
          this._searchHiddenEntityIds = new Set();
        }
        console.warn("Search Card: unable to load label metadata", err);
      }
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
          !this._matchesAnyRegex(searchableFields, excludedRegexes) &&
          !this._searchHiddenEntityIds.has(entity_id)
        ) {
          results.push({
            type: "entity",
            entity_id: entity_id,
          });
        }
      }

      return results.sort((a, b) => a.entity_id.localeCompare(b.entity_id));
    }

    _sortResults(results) {
      return results
        .map((result, index) => ({
          result: result,
          index: index,
        }))
        .sort((a, b) => {
          const rankDifference =
            this._getResultRank(a.result) - this._getResultRank(b.result);
          return rankDifference !== 0 ? rankDifference : a.index - b.index;
        })
        .map(({ result }) => result);
    }

    _getResultRank(result) {
      if (
        result.type === "entity" &&
        this._searchPriorityEntityIds.has(result.entity_id)
      ) {
        return 0;
      }

      if (result.type === "local_service") {
        return 1;
      }

      return result.type === "navigation" ? 2 : 3;
    }

    _getNavigationResults(searchRegex) {
      const seen = new Set();
      // hass.panels is the authenticated get_panels result, including dashboard registrations.
      return Object.values(this.hass.panels || {}).flatMap((panel) => {
        if (!panel || typeof panel.title !== "string" || !panel.title.trim() ||
            typeof panel.url_path !== "string" || !isNavigationPath(`/${panel.url_path}`) ||
            ["app", "notfound", "_my_redirect"].includes(panel.url_path) ||
            panel.show_in_sidebar === false || panel.default_visible === false) return [];
        const path = `/${panel.url_path}`;
        if (seen.has(path)) return [];
        seen.add(path);
        const name = Object.hasOwn(PANEL_NAMES, panel.title) ? PANEL_NAMES[panel.title] : panel.title;
        const fields = [name, panel.title, panel.url_path,
          panel.component_name === "lovelace" ? "dashboard" : panel.component_name];
        if (!fields.some((field) => typeof field === "string" && searchRegex.test(field))) return [];
        return [{ type: "navigation", panel: { name, path,
          icon: (typeof panel.icon === "string" && panel.icon) ||
            (Object.hasOwn(PANEL_ICONS, panel.component_name) ? PANEL_ICONS[panel.component_name] : "mdi:view-dashboard") } }];
      }).sort((a, b) => a.panel.name.localeCompare(b.panel.name));
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
        padding: 8px 12px;
        color: var(--secondary-text-color);
      }
      #results {
        position: fixed;
        inset: auto;
        margin: 0;
        box-sizing: border-box;
        padding: 4px;
        overflow: auto;
        overscroll-behavior: contain;
        overflow-anchor: none;
        background: var(--ha-card-background, var(--card-background-color, white));
        color: var(--primary-text-color);
        border: 1px solid var(--divider-color, #ddd);
        border-radius: var(--ha-card-border-radius, 12px);
        box-shadow: 0 8px 24px #0004;
        z-index: 1000;
      }
      #results[hidden] { display: none !important; }
      #results > :not(#count) { min-height: 48px; }
      .load-more {
        display: block;
        width: 100%;
        color: var(--primary-color);
        background: transparent;
        border: 0;
        cursor: pointer;
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
              <span class="label">Max initial results</span>
              <input type="number" min="1" step="1"
                .value=${String(initialResultLimit(config.max_results))}
                @input=${(ev) => this._updateConfigField("max_results", initialResultLimit(Number(ev.target.value)))} />
              <span class="helper">Approximate visible rows before scrolling. All matches remain available.</span>
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
