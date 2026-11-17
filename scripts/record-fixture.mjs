// Records what an ext-apps example server answers, so tests can replay it without a running server.
// Usage: node scripts/record-fixture.mjs <port> <output.json>
// It lists the tools and calls every tool that has no required arguments, keeping the first result of each.
import { writeFileSync } from 'node:fs';

const [port, output] = process.argv.slice(2);
const url = `http://localhost:${port}/mcp`;
let id = 0;

async function rpc(method, params) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++id, method, params }),
  });
  const text = await response.text();
  const data = text.startsWith('event:') || text.includes('\ndata:') || text.startsWith('data:')
    ? text.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).pop()
    : text;
  const message = JSON.parse(data);
  if (message.error) throw new Error(`${method}: ${message.error.message}`);
  return message.result;
}

const { tools } = await rpc('tools/list', {});
const results = {};
for (const tool of tools) {
  const required = tool.inputSchema?.required ?? [];
  if (required.length > 0) continue;
  try {
    results[tool.name] = await rpc('tools/call', { name: tool.name, arguments: {} });
  } catch (error) {
    console.error(`skipped ${tool.name}: ${error.message}`);
  }
}
const resources = {};
for (const tool of tools) {
  const uri = tool._meta?.ui?.resourceUri ?? tool._meta?.['ui/resourceUri'];
  if (uri && !resources[uri]) {
    const read = await rpc('resources/read', { uri });
    resources[uri] = (read.contents?.[0]?._meta) ?? {};
  }
}
writeFileSync(output, JSON.stringify({ tools, results, resourceMeta: resources }, null, 1));
console.log(`${output}: ${tools.length} tools, ${Object.keys(results).length} results`);
