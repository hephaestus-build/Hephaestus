import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { zipSync } from "fflate";

import { installPinnedBinary } from "./lib/pinned-binary.ts";

const executable = new TextEncoder().encode("verified fixture executable");
const archive = zipSync({ "fixture.exe": executable });

for (const scenario of ["http", "network", "body"] as const) {
	void test(`a pinned download recovers from a temporary ${scenario} failure`, async (t) => {
		const cache = await mkdtemp(path.join(tmpdir(), "pinned-retry-"));
		t.after(async () => rm(cache, { recursive: true, force: true }));
		let calls = 0;
		t.mock.method(globalThis, "fetch", async (input: Parameters<typeof fetch>[0]) => {
			const url = input instanceof Request ? input.url : input.toString();
			if (url.startsWith("https://ghcr.io/")) {
				return new Response(null, { status: 404 });
			}
			calls += 1;
			if (calls === 1) {
				if (scenario === "http") {
					return new Response("unavailable", { status: 504 });
				}
				if (scenario === "network") {
					throw new DOMException("timeout", "TimeoutError");
				}
				return new Response(
					new ReadableStream({ start: (controller) => controller.error(new TypeError("reset")) }),
				);
			}
			return new Response(archive);
		});
		const binary = await installPinnedBinary({
			cache,
			sourceCache: path.join(cache, "source"),
			version: "1",
			asset: { name: "fixture.zip", sha256: createHash("sha256").update(archive).digest("hex") },
			url: "https://example.invalid/",
			executable: "fixture.exe",
			allowDownload: true,
		});
		assert.equal(calls, 2);
		assert.deepEqual(await readFile(binary), Buffer.from(executable));
	});
}

for (const scenario of ["unavailable", "not found", "corrupt"] as const) {
	void test(`a pinned download fails closed when ${scenario}`, async (t) => {
		const cache = await mkdtemp(path.join(tmpdir(), "pinned-retry-"));
		t.after(async () => rm(cache, { recursive: true, force: true }));
		let calls = 0;
		t.mock.method(globalThis, "fetch", async (input: Parameters<typeof fetch>[0]) => {
			const url = input instanceof Request ? input.url : input.toString();
			if (url.startsWith("https://ghcr.io/")) {
				return new Response(null, { status: 404 });
			}
			calls += 1;
			if (scenario === "unavailable") {
				return new Response("unavailable", { status: 504 });
			}
			if (scenario === "not found") {
				return new Response("missing", { status: 404 });
			}
			return new Response("corrupt archive");
		});
		await assert.rejects(
			installPinnedBinary({
				cache,
				sourceCache: path.join(cache, "source"),
				version: "1",
				asset: { name: "fixture.zip", sha256: createHash("sha256").update(archive).digest("hex") },
				url: "https://example.invalid/",
				executable: "fixture.exe",
				allowDownload: true,
			}),
			scenario === "corrupt" ? /checksum mismatch/u : /No verified source/u,
		);
		assert.equal(calls, scenario === "unavailable" ? 3 : 1);
		assert.deepEqual(await readdir(cache), [], "no failed download becomes an installed tool");
	});
}
