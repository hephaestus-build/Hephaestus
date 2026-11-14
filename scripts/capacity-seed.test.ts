import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, type IncomingMessage } from "node:http";
import { test } from "node:test";

import { releaseSilentMode } from "./lib/capacity-seed.ts";
import { asRecord, parseJson } from "./lib/json.ts";

interface Seen {
	method: string;
	url: string;
	authorization: string | undefined;
	ifMatch: string | undefined;
	body: string;
}

/** An instance-admin API with the ETag rule of `PATCH /admin/settings/silent-mode`: a release needs the current ETag. */
async function withSettings(
	engaged: boolean,
	run: (base: string, seen: Seen[], state: () => boolean) => Promise<void>,
	{ refuse = false }: { refuse?: boolean } = {},
): Promise<void> {
	let silent = engaged;
	let version = 4;
	const seen: Seen[] = [];
	const server = createServer((request: IncomingMessage, response) => {
		void (async () => {
			const chunks: Buffer[] = [];
			for await (const chunk of request) {
				chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
			}
			const body = Buffer.concat(chunks).toString("utf8");
			seen.push({
				method: request.method ?? "",
				url: request.url ?? "",
				authorization: request.headers.authorization,
				ifMatch: request.headers["if-match"],
				body,
			});
			if (request.method === "GET" && request.url === "/admin/settings") {
				response
					.writeHead(200, { "content-type": "application/json" })
					.end(JSON.stringify({ silentModeEngaged: silent, etag: `"${version}"` }));
			} else if (request.method === "PATCH" && request.url === "/admin/settings/silent-mode") {
				const wanted = asRecord(parseJson(body), "request").engaged === true;
				if (refuse) {
					response.writeHead(403).end();
				} else if (!wanted && request.headers["if-match"] === undefined) {
					response.writeHead(428).end();
				} else if (!wanted && request.headers["if-match"] !== `"${version}"`) {
					response.writeHead(412).end();
				} else {
					silent = wanted;
					version += 1;
					response
						.writeHead(200, { "content-type": "application/json" })
						.end(JSON.stringify({ silentModeEngaged: silent, etag: `"${version}"` }));
				}
			} else {
				response.writeHead(404).end();
			}
		})();
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new TypeError("the server listens on no port");
	}
	try {
		await run(`http://127.0.0.1:${address.port}`, seen, () => silent);
	} finally {
		server.close();
	}
}

await test("the drill releases Silent Mode through the admin API with the current ETag", async () => {
	await withSettings(true, async (base, seen, silent) => {
		assert.equal(await releaseSilentMode(base, "token-1"), false);
		assert.equal(silent(), false);
		assert.deepEqual(
			seen.map(({ method, url }) => `${method} ${url}`),
			["GET /admin/settings", "PATCH /admin/settings/silent-mode", "GET /admin/settings"],
		);
		assert.ok(seen.every((call) => call.authorization === "Bearer token-1"));
		const patch = seen.find((call) => call.method === "PATCH");
		assert.ok(patch);
		assert.equal(patch.ifMatch, '"4"');
		assert.equal(patch.body, '{"engaged":false}');
	});
});

await test("the drill changes nothing when Silent Mode is already released", async () => {
	await withSettings(false, async (base, seen) => {
		assert.equal(await releaseSilentMode(base, "token-1"), false);
		assert.deepEqual(
			seen.map(({ method }) => method),
			["GET", "GET"],
		);
	});
});

await test("the drill reports Silent Mode that stays engaged, and fails on a refused call", async () => {
	await withSettings(
		true,
		async (base) => {
			await assert.rejects(releaseSilentMode(base, "token-1"), /PATCH: HTTP 403/u);
		},
		{ refuse: true },
	);
});
