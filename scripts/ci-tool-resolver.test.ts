import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { allCiToolPins, ciToolPin } from "./lib/ci-tool-catalog.ts";
import { resolveCiTool, toolCacheDirectory, type ToolSource } from "./lib/ci-tool-resolver.ts";

const bytes = new TextEncoder().encode("pinned tool fixture");
const pin = {
	tool: "fixture",
	version: "1.2.3",
	name: "fixture.bin",
	url: "https://example.invalid/fixture.bin",
	sha256: createHash("sha256").update(bytes).digest("hex"),
};

void test("a verified cache hit needs no upstream request", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "ci-tool-cache-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const directory = toolCacheDirectory(pin, root);
	await mkdir(directory, { recursive: true });
	await writeFile(path.join(directory, "asset"), bytes);
	const result = await resolveCiTool(pin, {
		cacheRoot: root,
		sources: [
			{
				name: "github-api",
				read: async () => {
					throw new Error("network must not run");
				},
			},
		],
	});
	assert.equal(result.source, "cache");
	assert.deepEqual(result.bytes, Buffer.from(bytes));
});

for (const served of ["ghcr", "github-api", "direct"] as const) {
	void test(`${served} fallback supplies verified bytes and populates the cache`, async (t) => {
		const root = await mkdtemp(path.join(tmpdir(), "ci-tool-source-"));
		t.after(async () => rm(root, { recursive: true, force: true }));
		const calls: string[] = [];
		const sources = (["ghcr", "github-api", "direct"] as const).map((name) => ({
			name,
			read: async () => {
				calls.push(name);
				if (name !== served) {
					throw new Error("unavailable");
				}
				return bytes;
			},
		}));
		const result = await resolveCiTool(pin, { cacheRoot: root, sources });
		assert.equal(result.source, served);
		assert.deepEqual(await readFile(result.file), Buffer.from(bytes));
		assert.deepEqual(
			calls,
			["ghcr", "github-api", "direct"].slice(
				0,
				["ghcr", "github-api", "direct"].indexOf(served) + 1,
			),
		);
	});
}

void test("checksum mismatches at cache, GHCR and API are refused before the direct source succeeds", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "ci-tool-mismatch-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	await mkdir(toolCacheDirectory(pin, root), { recursive: true });
	await writeFile(path.join(toolCacheDirectory(pin, root), "asset"), "corrupt cache");
	const logs: string[] = [];
	const result = await resolveCiTool(pin, {
		cacheRoot: root,
		log: (line) => {
			logs.push(line);
		},
		sources: [
			{ name: "ghcr", read: async () => new TextEncoder().encode("corrupt mirror") },
			{ name: "github-api", read: async () => new TextEncoder().encode("corrupt API") },
			{ name: "direct", read: async () => bytes },
		],
	});
	assert.equal(result.source, "direct");
	assert.equal(logs.filter((line) => line.includes("checksum mismatch")).length, 3);
	assert.deepEqual(await readFile(result.file), Buffer.from(bytes));
});

void test("all failed or mismatched sources produce a clear refusal", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "ci-tool-refusal-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const sources: ToolSource[] = [
		{ name: "ghcr", read: async () => new TextEncoder().encode("corrupt mirror") },
		{
			name: "github-api",
			read: async () => {
				throw new Error("unavailable");
			},
		},
		{ name: "direct", read: async () => new TextEncoder().encode("corrupt direct source") },
	];
	await assert.rejects(
		resolveCiTool(pin, { cacheRoot: root, sources }),
		/No verified source.*fixture@1\.2\.3.*github-api:.*direct: checksum mismatch/u,
	);
});

void test(
	"a restored directory symlink is refused without writing through it",
	{ skip: process.platform === "win32" },
	async (t) => {
		const root = await mkdtemp(path.join(tmpdir(), "ci-tool-link-"));
		t.after(async () => rm(root, { recursive: true, force: true }));
		const outside = path.join(root, "outside");
		await mkdir(outside);
		await writeFile(path.join(outside, "asset"), "outside data");
		await symlink(outside, toolCacheDirectory(pin, root), "dir");
		await resolveCiTool(pin, {
			cacheRoot: root,
			sources: [{ name: "direct", read: async () => bytes }],
		});
		assert.equal(await readFile(path.join(outside, "asset"), "utf8"), "outside data");
	},
);

void test("the catalog selects each supported platform and preserves the Cosign trust pins", async () => {
	const pins = await allCiToolPins();
	assert.ok(pins.length > 25);
	const bootstrap = await ciToolPin("cosign-bootstrap", "linux-x64");
	const key = await ciToolPin("cosign", "key");
	const amd64 = await ciToolPin("trivy", "linux-x64");
	const arm64 = await ciToolPin("trivy", "linux-arm64");
	assert.equal(
		bootstrap.sha256,
		"4629c757b7618056f8ddd7e2625ae9fdd94c0372a65049520bc7d9df9efc7f71",
	);
	assert.equal(key.sha256, "f4cea466e5e887a45da5031757fa1d32655d83420639dc1758749b744179f126");
	assert.notEqual(amd64.sha256, arm64.sha256);
	await assert.rejects(ciToolPin("../escape"), /Invalid CI tool/u);
});

void test("an unavailable cache filesystem does not discard verified upstream bytes", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "ci-tool-cache-io-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const blocked = path.join(root, "not-a-directory");
	await writeFile(blocked, "keep this file");
	const result = await resolveCiTool(pin, {
		cacheRoot: blocked,
		sources: [{ name: "direct", read: async () => bytes }],
	});
	assert.equal(result.cacheable, false);
	assert.deepEqual(result.bytes, bytes);
	assert.equal(await readFile(blocked, "utf8"), "keep this file");
});

void test("the default resolver tries GHCR, GitHub API and direct URL in order", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "ci-tool-order-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const subject = {
		...pin,
		url: "https://github.com/example/fixture/releases/download/v1.2.3/fixture.bin",
	};
	const calls: string[] = [];
	t.mock.method(globalThis, "fetch", async (input: Parameters<typeof fetch>[0]) => {
		const url = new URL(input instanceof Request ? input.url : input.toString());
		const source =
			url.hostname === "ghcr.io"
				? "ghcr"
				: url.hostname === "api.github.com"
					? "github-api"
					: "direct";
		calls.push(source);
		return source === "direct" ? new Response(bytes) : new Response(null, { status: 404 });
	});
	const resolved = await resolveCiTool(subject, { cacheRoot: root });
	assert.equal(resolved.source, "direct");
	assert.deepEqual([...new Set(calls)], ["ghcr", "github-api", "direct"]);
});
