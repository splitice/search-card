import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, '../search-card.js'), 'utf8');
const sha256 = createHash('sha256').update(source).digest('hex');
const reviewFile = resolve(root, 'compatibility.json');
if (process.argv[2] === '--record-review') {
  const commit = execFileSync('git', ['log', '-1', '--format=%H', '--', 'search-card.js'], { cwd: resolve(root, '..'), encoding: 'utf8' }).trim();
  writeFileSync(reviewFile, JSON.stringify({ source: '../search-card.js', reviewedCommit: commit, sha256 }, null, 2) + '\n');
  console.log('Recorded the reviewed card source. Review and commit this file together with parity tests.');
  process.exit(0);
}
const review = JSON.parse(readFileSync(reviewFile, 'utf8'));
if (review.sha256 !== sha256) {
  throw new Error('search-card.js changed. Review the diff, update Kotlin/fixtures, then run node tools/reference.mjs --record-review and all checks.');
}

const definitions = new Map();
class LitElement { requestUpdate() {} }
const navigationRequests = [];
const navigationEvents = [];
const cardTools = { LitElement, createThing: (_type, config) => config,
  LitHtml: (strings, ...values) => ({ strings, values }) };
definitions.set('card-tools', cardTools);
const context = vm.createContext({
  customElements: { whenDefined: () => Promise.resolve(), get: name => definitions.get(name), define: (name, value) => definitions.set(name, value) },
  cardTools, window: {
    location: { pathname: '/dashboard-phone/main', search: '', hash: '' },
    history: { state: {}, pushState: (_state, _title, path) => navigationRequests.push(path) },
    dispatchEvent: event => navigationEvents.push(event.type),
  }, CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } }, HTMLElement: class {},
  setTimeout: () => 0, clearTimeout: () => {}, console: { warn() {} },
});
vm.runInContext(source, context, { timeout: 5000 });
await Promise.resolve();
const SearchCard = definitions.get('search-card');
if (!SearchCard) throw new Error('Reference harness could not load the card; update the harness for the new card structure.');
const fixtures = JSON.parse(readFileSync(resolve(root, 'fixtures/search.json'), 'utf8'));
const reference = fixtures.cases.map(fixture => {
  const input = { ...fixtures.base, ...fixture, config: { ...fixtures.base.config, ...fixture.config } };
  if (input.generated_entities) {
    input.states = Object.fromEntries(Array.from({ length: input.generated_entities }, (_, index) => {
      const id = `sensor.bulk_${String(index).padStart(3, '0')}`;
      return [id, { entity_id: id, state: "on", attributes: {} }];
    }));
  }
  const card = new SearchCard();
  card.setConfig(input.config);
  card.hass = { states: input.states, services: input.services, panels: input.panels ?? {} };
  card._searchPriorityEntityIds = new Set(input.priority ?? []);
  card._searchHiddenEntityIds = new Set(input.hidden ?? []);
  card._entityDeviceNames = new Map(Object.entries(input.entity_device_names ?? {}));
  card._performSearch(input.query);
  const actions = card._activeActions.map(([action, matches]) => {
    const row = card._createActionRow(action, matches);
    return { name: row.name, service: row.service, service_data: row.service_data, icon: row.icon };
  });
  navigationRequests.length = 0;
  navigationEvents.length = 0;
  const results = card._results;
  if (input.expected_entities) {
    assert.deepEqual(Array.from(results.filter(result => result.type === 'entity'), result => result.entity_id),
      input.expected_entities, fixture.name);
  }
  if (input.expected_types) assert.deepEqual(Array.from(results, result => result.type), input.expected_types, fixture.name);
  if (input.generated_entities) {
    assert.equal(results.length, input.generated_entities, 'Never truncate matches at the legacy limit');
    assert.equal(results.at(-1).entity_id, 'sensor.bulk_249');
  }
  for (const result of results.filter(result => result.type === 'navigation')) {
    const row = card._createResultRow(result);
    const click = row.values.find((_value, index) => row.strings[index].endsWith('@click='));
    assert.equal(typeof click, 'function');
    click();
  }
  const navigations = [...navigationRequests];
  assert.deepEqual(navigations, Array.from(results.filter(result => result.type === 'navigation'), result => result.panel.path));
  assert.ok(navigationEvents.every(type => type === 'location-changed'));
  assert.equal(navigationEvents.length, navigations.length);
  if (navigations.length) {
    const result = results.find(result => result.type === 'navigation');
    const row = card._createResultRow(result);
    const keydown = row.values.find((_value, index) => row.strings[index].endsWith('@keydown='));
    for (const key of ['Enter', ' ']) {
      let prevented = false;
      keydown({ key, preventDefault() { prevented = true; } });
      assert.ok(prevented);
      assert.equal(navigationRequests.at(-1), result.panel.path);
    }
  }
  const beforeInvalid = navigationRequests.length;
  card._openNavigation('//external.example/path');
  card._openNavigation('/../config');
  assert.equal(navigationRequests.length, beforeInvalid, 'Only internal panel paths may navigate');
  return { name: fixture.name, output: { results, total: card._results.length, actions }, navigations };
});
const output = process.argv[2] ?? resolve(root, 'core/build/reference.json');
const dynamic = new SearchCard();
dynamic.setConfig({});
dynamic._searchValue = 'Energy';
dynamic.hass = { states: {}, services: {}, panels: {} };
assert.equal(dynamic._results.length, 0);
dynamic.hass = { states: {}, services: {}, panels: {
  energy: { title: 'energy', url_path: 'energy', component_name: 'energy' },
} };
assert.equal(dynamic._results[0].panel.path, '/energy');
dynamic.hass = { states: {}, services: {}, panels: {} };
assert.equal(dynamic._results.length, 0, 'Removed panels disappear from an active search');
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, JSON.stringify(reference, null, 2) + '\n');
console.log(`Executed ${reference.length} cases against the actual search-card.js (${sha256.slice(0, 12)}).`);
