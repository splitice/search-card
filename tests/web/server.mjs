import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
const bundle = await build({ entryPoints: ['tests/web/harness.js'], bundle: true, write: false, format: 'esm', external: ['/search-card.js'] });
const routes = {
  '/': ['text/html', '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><script type="module" src="/harness.js"></script>'],
  '/harness.js': ['text/javascript', bundle.outputFiles[0].text],
  '/search-card.js': ['text/javascript', await readFile('search-card.js', 'utf8')],
};
createServer((request, response) => {
  const route = routes[request.url];
  response.writeHead(route ? 200 : 404, { 'Content-Type': route?.[0] ?? 'text/plain' });
  response.end(route?.[1] ?? 'Not found');
}).listen(4173, '127.0.0.1');
