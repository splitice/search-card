import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import vm from 'node:vm';

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
const cardTools = { LitElement, createThing: (_type, config) => config };
definitions.set('card-tools', cardTools);
const context = vm.createContext({
  customElements: { whenDefined: () => Promise.resolve(), get: name => definitions.get(name), define: (name, value) => definitions.set(name, value) },
  cardTools, window: {}, HTMLElement: class {},
  setTimeout: () => 0, clearTimeout: () => {}, console: { warn() {} },
});
vm.runInContext(source, context, { timeout: 5000 });
await Promise.resolve();
const SearchCard = definitions.get('search-card');
if (!SearchCard) throw new Error('Reference harness could not load the card; update the harness for the new card structure.');
const fixtures = JSON.parse(readFileSync(resolve(root, 'fixtures/search.json'), 'utf8'));
const reference = fixtures.cases.map(fixture => {
  const input = { ...fixtures.base, ...fixture, config: { ...fixtures.base.config, ...fixture.config } };
  const card = new SearchCard();
  card.setConfig(input.config);
  card.hass = { states: input.states, services: input.services };
  card._searchPriorityEntityIds = new Set(input.priority ?? []);
  card._searchHiddenEntityIds = new Set(input.hidden ?? []);
  card._performSearch(input.query);
  const actions = card._activeActions.map(([action, matches]) => {
    const row = card._createActionRow(action, matches);
    return { name: row.name, service: row.service, service_data: row.service_data, icon: row.icon };
  });
  return { name: fixture.name, output: { results: card._results.slice(0, card.max_results), total: card._results.length, actions } };
});
const output = process.argv[2] ?? resolve(root, 'core/build/reference.json');
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, JSON.stringify(reference, null, 2) + '\n');
console.log(`Executed ${reference.length} cases against the actual search-card.js (${sha256.slice(0, 12)}).`);
