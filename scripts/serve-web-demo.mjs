// Serves the web demo on two origins: the host page on :8080 and the sandbox proxy on :8081.
// The two ports are two origins, which is what the sandbox needs. Usage: node scripts/serve-web-demo.mjs
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const hostDir = join(root, 'samples/demo/shared/build/dist/wasmJs/developmentExecutable');
const sandboxDir = join(root, 'web-sandbox');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm',
  '.json': 'application/json', '.css': 'text/css', '.txt': 'text/plain', '.png': 'image/png', '.ttf': 'font/ttf' };

function serve(dir, port) {
  return createServer(async (req, res) => {
    const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname)).replace(/^(\.\.[/\\])+/, '');
    const file = join(dir, path.endsWith('/') ? path + 'index.html' : path);
    if (!file.startsWith(dir)) { res.writeHead(403).end(); return; }
    try {
      const body = await readFile(file);
      res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
      res.end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  }).listen(port, '127.0.0.1', () => console.log(`${dir} on http://localhost:${port}`));
}

serve(hostDir, 8080);
serve(sandboxDir, 8081);
