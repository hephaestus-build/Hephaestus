import assert from "node:assert/strict";
import { test } from "node:test";

import { downloadReleaseAsset } from "./lib/release-asset.ts";

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
					headers: {
						location:
							"https://release-assets.githubusercontent.com/fixture?jwt=delegated-signature",
					},
				});
			}
			assert.equal(
				url,
				"https://release-assets.githubusercontent.com/fixture?jwt=delegated-signature",
			);
			assert.equal(headers.get("Authorization"), null);
			return new Response("archive");
		},
	);
	assert.equal(new TextDecoder().decode(await downloadReleaseAsset(direct)), "archive");
	assert.deepEqual(calls, [
		tag,
		asset,
		"https://release-assets.githubusercontent.com/fixture?jwt=delegated-signature",
	]);
});

void test("API failure falls back to the authenticated GitHub direct URL", async (t) => {
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
			assert.equal(new Headers(init?.headers).get("Authorization"), "Bearer fixture-token");
			return new Response("archive");
		},
	);
	assert.equal(new TextDecoder().decode(await downloadReleaseAsset(direct)), "archive");
	assert.deepEqual(calls, [tag, tag, direct]);
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
	assert.deepEqual(calls, [tag, asset, asset, asset, tag, asset, asset, asset, direct]);
});

for (const status of [403, 618]) {
	void test(`a failed authenticated asset (${status}) uses the public API before the release host`, async (t) => {
		const previous = process.env.GITHUB_TOKEN;
		process.env.GITHUB_TOKEN = "fixture-token";
		t.after(() => {
			if (previous === undefined) {
				delete process.env.GITHUB_TOKEN;
			} else {
				process.env.GITHUB_TOKEN = previous;
			}
		});
		const calls: { url: string; authenticated: boolean }[] = [];
		t.mock.method(
			globalThis,
			"fetch",
			async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
				const url = input instanceof Request ? input.url : input.toString();
				const authenticated = new Headers(init?.headers).has("Authorization");
				calls.push({ url, authenticated });
				if (url === tag) {
					return Response.json(metadata);
				}
				assert.equal(url, asset);
				return authenticated
					? new Response("unavailable", { status })
					: new Response("verified archive");
			},
		);
		assert.equal(new TextDecoder().decode(await downloadReleaseAsset(direct)), "verified archive");
		assert.deepEqual(calls, [
			{ url: tag, authenticated: true },
			{ url: asset, authenticated: true },
			{ url: tag, authenticated: false },
			{ url: asset, authenticated: false },
		]);
	});
}

void test("a redirect between GitHub API paths retains authentication", async (t) => {
	const previous = process.env.GITHUB_TOKEN;
	process.env.GITHUB_TOKEN = "fixture-token";
	t.after(() => {
		if (previous === undefined) {
			delete process.env.GITHUB_TOKEN;
		} else {
			process.env.GITHUB_TOKEN = previous;
		}
	});
	const canonical = "https://api.github.com/repositories/1/releases/assets/123";
	t.mock.method(
		globalThis,
		"fetch",
		async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
			const url = input instanceof Request ? input.url : input.toString();
			assert.equal(new Headers(init?.headers).get("Authorization"), "Bearer fixture-token");
			if (url === tag) {
				return Response.json(metadata);
			}
			if (url === asset) {
				return new Response(null, { status: 302, headers: { location: canonical } });
			}
			assert.equal(url, canonical);
			return new Response("archive");
		},
	);
	assert.equal(new TextDecoder().decode(await downloadReleaseAsset(direct)), "archive");
});
