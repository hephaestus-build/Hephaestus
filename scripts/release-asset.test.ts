import assert from "node:assert/strict";
import { test } from "node:test";

import { downloadReleaseAsset } from "./lib/release-asset.ts";
import { checksumFor, verifyChecksum } from "./setup-release-security-tools.ts";

const direct = "https://github.com/example/tool/releases/download/v1.2.3/tool.tar.gz";
const tag = "https://api.github.com/repos/example/tool/releases/tags/v1.2.3";
const asset = "https://api.github.com/repos/example/tool/releases/assets/123";
const metadata = {
	tag_name: "v1.2.3",
	draft: false,
	assets: [{ name: "tool.tar.gz", state: "uploaded", id: 123 }],
};

void test("API asset downloads use the exact release asset and remove credentials on redirects", async (t) => {
	const previous = process.env.GITHUB_TOKEN;
	process.env.GITHUB_TOKEN = "fixture-token";
	t.after(() => {
		if (previous === undefined) {
			delete process.env.GITHUB_TOKEN;
		} else {
			process.env.GITHUB_TOKEN = previous;
		}
	});
	const calls: string[] = [];
	t.mock.method(
		globalThis,
		"fetch",
		async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
			const url = input instanceof Request ? input.url : input.toString();
			calls.push(url);
			const headers = new Headers(init?.headers);
			assert.equal(init?.redirect, "manual");
			if (url === tag) {
				assert.equal(headers.get("Authorization"), "Bearer fixture-token");
				return Response.json(metadata);
			}
			if (url === asset) {
				assert.equal(headers.get("Accept"), "application/octet-stream");
				assert.equal(headers.get("Authorization"), "Bearer fixture-token");
				return new Response(null, {
					status: 302,
					headers: { location: "https://release-assets.githubusercontent.com/fixture" },
				});
			}
			assert.equal(url, "https://release-assets.githubusercontent.com/fixture");
			assert.equal(headers.get("Authorization"), null);
			return new Response("archive");
		},
	);
	assert.equal(new TextDecoder().decode(await downloadReleaseAsset(direct)), "archive");
	assert.deepEqual(calls, [tag, asset, "https://release-assets.githubusercontent.com/fixture"]);
});

void test("API failure falls back to the pinned direct URL without its token", async (t) => {
	const previous = process.env.GITHUB_TOKEN;
	process.env.GITHUB_TOKEN = "fixture-token";
	t.after(() => {
		if (previous === undefined) {
			delete process.env.GITHUB_TOKEN;
		} else {
			process.env.GITHUB_TOKEN = previous;
		}
	});
	const calls: string[] = [];
	t.mock.method(
		globalThis,
		"fetch",
		async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
			const url = input instanceof Request ? input.url : input.toString();
			calls.push(url);
			if (url === tag) {
				return new Response("unavailable", { status: 404 });
			}
			assert.equal(url, direct);
			assert.equal(new Headers(init?.headers).has("Authorization"), false);
			return new Response("archive");
		},
	);
	assert.equal(new TextDecoder().decode(await downloadReleaseAsset(direct)), "archive");
	assert.deepEqual(calls, [tag, direct]);
});

void test("checksum lookup rejects missing, duplicate and changed security-tool subjects", () => {
	const hash = "a".repeat(64);
	assert.equal(checksumFor(`${hash}  tool.tar.gz\n`, "tool.tar.gz"), hash);
	assert.throws(() => checksumFor(`${hash}  other.tar.gz\n`, "tool.tar.gz"));
	assert.throws(() => checksumFor(`${hash}  tool.tar.gz\n${hash}  tool.tar.gz\n`, "tool.tar.gz"));
	assert.throws(
		() => verifyChecksum(new TextEncoder().encode("changed"), hash),
		/checksum differs/u,
	);
});

void test("an unavailable API asset is retried before the direct fallback", async (t) => {
	const previous = process.env.GITHUB_TOKEN;
	process.env.GITHUB_TOKEN = "fixture-token";
	t.after(() => {
		if (previous === undefined) {
			delete process.env.GITHUB_TOKEN;
		} else {
			process.env.GITHUB_TOKEN = previous;
		}
	});
	const calls: string[] = [];
	t.mock.method(globalThis, "fetch", async (input: Parameters<typeof fetch>[0]) => {
		const url = input instanceof Request ? input.url : input.toString();
		calls.push(url);
		if (url === tag) {
			return Response.json(metadata);
		}
		if (url === asset) {
			return new Response("unavailable", { status: 504 });
		}
		assert.equal(url, direct);
		return new Response("archive");
	});
	assert.equal(new TextDecoder().decode(await downloadReleaseAsset(direct)), "archive");
	assert.deepEqual(calls, [tag, asset, asset, asset, direct]);
});
