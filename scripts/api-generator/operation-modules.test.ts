import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import test from "node:test";

const byName = (left: string, right: string) => left.localeCompare(right, "en");

const api = new URL("../../webapp/src/api/", import.meta.url);

async function moduleNames(directory: string): Promise<string[]> {
	const files = await readdir(new URL(directory, api));
	return files.map((file) => file.replace(/\.gen\.ts$/u, "")).toSorted(byName);
}

// webapp.config.ts routes symbols by their metadata and names. A symbol that it no longer recognizes
// falls back to the plugin file without an error, so its operation loads with the entry again.
await test("each operation has its own SDK and query module, and the plugin files hold none", async () => {
	const spec = await readFile(new URL("../../server/openapi.yaml", import.meta.url), "utf8");
	const operations = [...spec.matchAll(/^\s+operationId: (?<id>\S+)$/gmu)]
		.map((match) => match.groups?.id ?? "")
		.toSorted(byName);
	assert.ok(operations.length > 0);

	assert.deepEqual(await moduleNames("sdk/"), operations);
	assert.deepEqual(await moduleNames("@tanstack/react-query/"), operations);

	const sdk = await readFile(new URL("sdk.gen.ts", api), "utf8");
	assert.doesNotMatch(sdk, /\burl:\s*["']/u);
	const query = await readFile(new URL("@tanstack/react-query.gen.ts", api), "utf8");
	assert.doesNotMatch(query, /queryOptions\(|infiniteQueryOptions</u);
	assert.doesNotMatch(query, /mutationFn/u);
});
