import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import type { Server } from "node:http";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { test } from "node:test";

import {
	plantWrite,
	providerRecorder,
	writeProviderCertificates,
} from "./lib/release-upgrade-recorder.ts";
import {
	attribute,
	parseCalls,
	PLANTED_SOURCE,
	type ProviderCall,
	repeatedWrites,
	writeKey,
} from "./lib/release-upgrade-writes.ts";

function call(fields: Partial<ProviderCall>): ProviderCall {
	return {
		sequence: 1,
		source: "172.18.0.3",
		method: "POST",
		host: "api.github.com",
		path: "/repos/o/r/issues/1/comments",
		body: '{"body":"Hello"}',
		...fields,
	};
}

void test("a read, a GraphQL query and a credential exchange are no writes", () => {
	for (const read of [
		call({ method: "GET" }),
		call({ path: "/graphql", body: '{"query":"query Viewer { viewer { login } }"}' }),
		call({
			path: "/api/graphql",
			host: "gitlab.com",
			body: String.raw`{"query":"# a note\n{ currentUser { id } }"}`,
		}),
		call({ path: "/app/installations/42/access_tokens", body: "" }),
		call({ host: "gitlab.com", path: "/oauth/token" }),
		call({ host: "slack.com", path: "/api/conversations.history" }),
		call({ host: "slack.com", path: "/api/chat.getPermalink" }),
		call({ host: "slack.com", path: "/api/oauth.v2.access" }),
		call({ host: "outline.upgrade.test", path: "/api/documents.info" }),
	]) {
		assert.equal(writeKey(read), undefined, `${read.method} ${read.host}${read.path}`);
	}
});

void test("a REST write, a GraphQL mutation and a Slack or Outline post are writes", () => {
	for (const write of [
		call({}),
		call({ method: "PATCH", path: "/repos/o/r/issues/comments/7" }),
		call({
			path: "/graphql",
			body: '{"query":"mutation { addComment(input: {}) { clientMutationId } }"}',
		}),
		call({ path: "/graphql", body: "not json" }),
		call({ host: "slack.com", path: "/api/chat.postMessage", body: "channel=D1&text=Hi" }),
		call({ host: "outline.upgrade.test", path: "/api/documents.create" }),
	]) {
		assert.notEqual(writeKey(write), undefined, `${write.method} ${write.host}${write.path}`);
	}
	assert.notEqual(writeKey(call({ body: "a" })), writeKey(call({ body: "b" })));
});

void test("a write repeats only against one database, and an unknown caller is named", () => {
	const live = new Map([
		["172.18.0.3", "hephaestus"],
		["172.18.0.4", "hephaestus_partial"],
	]);
	const { attributed, unknown } = attribute(
		[
			call({ sequence: 1 }),
			call({ sequence: 2, source: "172.18.0.4" }),
			call({ sequence: 3, method: "GET" }),
			call({ sequence: 4, method: "GET" }),
			call({ sequence: 5, source: "172.18.0.9" }),
		],
		live,
	);
	assert.deepEqual(repeatedWrites(attributed), []);
	assert.deepEqual(
		unknown.map(({ sequence }) => sequence),
		[5],
	);
	const repeated = repeatedWrites([
		...attributed,
		{ database: "hephaestus", call: call({ sequence: 6 }) },
	]);
	assert.equal(repeated.length, 1);
	assert.match(
		repeated[0] ?? "",
		/^hephaestus: POST api\.github\.com\/repos\/o\/r\/issues\/1\/comments .* \(2 times\)$/u,
	);
});

function portOf(server: Server): number {
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new TypeError("the recorder listens on no port");
	}
	return address.port;
}

void test("the recorder answers a provider host over TLS and catches a planted double write", async () => {
	const directory = mkdtempSync(nodePath.join(tmpdir(), "upgrade-recorder-"));
	const files = writeProviderCertificates(directory);
	const { providers, control } = providerRecorder({
		key: readFileSync(files.key, "utf8"),
		cert: readFileSync(files.cert, "utf8"),
	});
	providers.listen(0, "127.0.0.1");
	control.listen(0, "127.0.0.1");
	await Promise.all([once(providers, "listening"), once(control, "listening")]);
	try {
		const ca = readFileSync(files.ca, "utf8");
		const port = portOf(providers);
		for (let attempt = 0; attempt < 2; attempt += 1) {
			assert.equal(
				await plantWrite(port, ca, "slack.com", "/api/chat.postMessage", '{"text":"once"}'),
				200,
			);
		}
		const response = await fetch(`http://127.0.0.1:${portOf(control)}/calls?after=0`);
		const calls = parseCalls(await response.text());
		assert.deepEqual(
			calls.map(({ sequence, source, host }) => ({ sequence, source, host })),
			[
				{ sequence: 1, source: PLANTED_SOURCE, host: "slack.com" },
				{ sequence: 2, source: PLANTED_SOURCE, host: "slack.com" },
			],
		);
		const { attributed } = attribute(calls, new Map());
		assert.equal(repeatedWrites(attributed).length, 1);
	} finally {
		providers.close();
		control.close();
		rmSync(directory, { recursive: true, force: true });
	}
});
