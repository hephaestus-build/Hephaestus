import assert from "node:assert/strict";
import { test, type TestContext } from "node:test";
import { toolSha256 } from "./lib/ci-tool-integrity.ts";
import { fetchMirroredCiTool, publishCiToolGroup } from "./lib/ci-tool-registry.ts";
import { asRecord, parseJson } from "./lib/json.ts";

const bytes = new TextEncoder().encode("verified fixture");
const pin = {
	tool: "fixture",
	version: "1.2.3",
	name: "fixture.bin",
	url: "https://example.invalid/fixture.bin",
	sha256: toolSha256(bytes),
};
const base = "https://ghcr.io/v2/hephaestus-build/ci-tools/fixture";

function credentials(t: TestContext) {
	const token = process.env.GITHUB_TOKEN;
	const actor = process.env.GITHUB_ACTOR;
	process.env.GITHUB_TOKEN = "fixture-github-token";
	process.env.GITHUB_ACTOR = "fixture-actor";
	t.after(() => {
		if (token === undefined) delete process.env.GITHUB_TOKEN;
		else process.env.GITHUB_TOKEN = token;
		if (actor === undefined) delete process.env.GITHUB_ACTOR;
		else process.env.GITHUB_ACTOR = actor;
	});
}

void test("GHCR authenticates its pull and preserves signed storage credentials without leaking the bearer", async (t) => {
	credentials(t);
	const calls: string[] = [];
	t.mock.method(
		globalThis,
		"fetch",
		async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
			const url = input instanceof Request ? input.url : input.toString();
			calls.push(url);
			const headers = new Headers(init?.headers);
			if (url.startsWith("https://ghcr.io/token?")) {
				assert.equal(
					headers.get("authorization"),
					`Basic ${Buffer.from("fixture-actor:fixture-github-token").toString("base64")}`,
				);
				assert.equal(
					new URL(url).searchParams.get("scope"),
					"repository:hephaestus-build/ci-tools/fixture:pull",
				);
				return Response.json({ token: "scoped-token" });
			}
			if (url === `${base}/manifests/1.2.3`) {
				assert.equal(headers.get("authorization"), "Bearer scoped-token");
				return Response.json({
					schemaVersion: 2,
					mediaType: "application/vnd.oci.image.manifest.v1+json",
					artifactType: "application/vnd.hephaestus.ci-tool.v1",
					layers: [{ digest: `sha256:${pin.sha256}` }],
				});
			}
			if (url === `${base}/blobs/sha256:${pin.sha256}`)
				return new Response(null, {
					status: 307,
					headers: { location: "https://pkg-storage.githubusercontent.com/fixture?sig=signed" },
				});
			assert.equal(url, "https://pkg-storage.githubusercontent.com/fixture?sig=signed");
			assert.equal(headers.get("authorization"), null);
			return new Response(bytes);
		},
	);
	assert.deepEqual(await fetchMirroredCiTool(pin), bytes);
	assert.equal(calls.length, 4);
});

void test("the mirror publishes checksum-addressed blobs and the complete version manifest", async (t) => {
	credentials(t);
	let manifest: Record<string, unknown> | undefined;
	const uploads: string[] = [];
	t.mock.method(
		globalThis,
		"fetch",
		async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
			const url = input instanceof Request ? input.url : input.toString();
			if (url.startsWith("https://ghcr.io/token?")) {
				assert.equal(
					new URL(url).searchParams.get("scope"),
					"repository:hephaestus-build/ci-tools/fixture:pull,push",
				);
				return Response.json({ token: "scoped-token" });
			}
			assert.equal(new Headers(init?.headers).get("authorization"), "Bearer scoped-token");
			if (init?.method === "HEAD") return new Response(null, { status: 404 });
			if (init?.method === "POST")
				return new Response(null, {
					status: 202,
					headers: { location: `${base}/blobs/uploads/fixture?state=opaque` },
				});
			assert.equal(init?.method, "PUT");
			if (url === `${base}/manifests/1.2.3`) {
				assert.equal(
					new Headers(init?.headers).get("content-type"),
					"application/vnd.oci.image.manifest.v1+json",
				);
				manifest = asRecord(parseJson(await new Response(init?.body).text()), "manifest");
			} else {
				const digest = new URL(url).searchParams.get("digest");
				assert.equal(
					digest,
					`sha256:${toolSha256(new Uint8Array(await new Response(init?.body).arrayBuffer()))}`,
				);
				uploads.push(digest);
			}
			return new Response(null, { status: 201 });
		},
	);
	await publishCiToolGroup([
		{ pin, bytes },
		{ pin, bytes },
	]);
	assert.deepEqual(uploads, [
		`sha256:${toolSha256(new TextEncoder().encode("{}"))}`,
		`sha256:${pin.sha256}`,
	]);
	assert.equal(manifest?.artifactType, "application/vnd.hephaestus.ci-tool.v1");
	assert.deepEqual(manifest?.layers, [
		{
			mediaType: "application/octet-stream",
			digest: `sha256:${pin.sha256}`,
			size: bytes.length,
			annotations: { "org.opencontainers.image.title": "fixture.bin" },
		},
	]);
});

void test("the mirror refuses mismatched bytes before making any request", async (t) => {
	t.mock.method(globalThis, "fetch", () => {
		throw new Error("network must not run");
	});
	await assert.rejects(
		publishCiToolGroup([{ pin, bytes: new TextEncoder().encode("tampered") }]),
		/Refusing an unverified/u,
	);
});

void test("a foreign upload location is refused before any credentials or bytes can leave GHCR", async (t) => {
	credentials(t);
	t.mock.method(
		globalThis,
		"fetch",
		async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
			const url = input instanceof Request ? input.url : input.toString();
			assert.ok(url.startsWith("https://ghcr.io/"));
			if (url.startsWith("https://ghcr.io/token?")) return Response.json({ token: "scoped-token" });
			if (init?.method === "HEAD") return new Response(null, { status: 404 });
			return new Response(null, {
				status: 202,
				headers: { location: "https://example.invalid/upload" },
			});
		},
	);
	await assert.rejects(publishCiToolGroup([{ pin, bytes }]), /Unsafe GHCR upload location/u);
});

void test("a rejected registry token is refreshed once and then fails closed", async (t) => {
	credentials(t);
	let tokens = 0,
		requests = 0;
	t.mock.method(globalThis, "fetch", async (input: Parameters<typeof fetch>[0]) => {
		if (
			(input instanceof Request ? input.url : input.toString()).startsWith("https://ghcr.io/token?")
		) {
			tokens += 1;
			return Response.json({ token: "rejected" });
		}
		requests += 1;
		return new Response(null, { status: 401 });
	});
	await assert.rejects(fetchMirroredCiTool(pin), /HTTP 401/u);
	assert.equal(tokens, 2);
	assert.equal(requests, 2);
});
