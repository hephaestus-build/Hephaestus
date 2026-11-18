import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { once } from "node:events";
import { mkdtempSync, readFileSync } from "node:fs";
import { createServer as createHttpServer } from "node:http";
import { createServer as createTcpServer, type Server } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { modelsUrl, probeEndpoint } from "./feedback-eval/endpoint-probe.ts";

const PROBE = path.join(import.meta.dirname, "feedback-eval", "endpoint-probe.ts");
const FAST = { connectMs: 500, responseMs: 500, attempts: 2 };

async function listening(server: Server): Promise<number> {
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(address !== null && typeof address === "object");
	return address.port;
}

/** A port that nothing listens on: a server takes it and gives it back. */
async function closedPort(): Promise<number> {
	const server = createTcpServer();
	const port = await listening(server);
	server.close();
	await once(server, "close");
	return port;
}

void test("the probe asks for the models list beside the chat completions the composer calls", () => {
	assert.equal(modelsUrl("https://llm.example/").href, "https://llm.example/v1/models");
	assert.equal(modelsUrl("https://llm.example/v1").href, "https://llm.example/v1/models");
	assert.equal(modelsUrl("https://llm.example/api/v1/").href, "https://llm.example/api/v1/models");
});

void test("any HTTP answer is reachable: the probe checks the connection, not the model", async () => {
	const seen: string[] = [];
	const server = createHttpServer((request, response) => {
		seen.push(`${request.url ?? ""} ${request.headers.authorization ?? ""}`);
		response.writeHead(404).end();
	});
	const port = await listening(server);
	try {
		assert.deepEqual(await probeEndpoint(`http://127.0.0.1:${String(port)}`, "k", FAST), {
			reachable: true,
			status: 404,
		});
		// One request, to the models list, with the key.
		assert.deepEqual(seen, ["/v1/models Bearer k"]);
	} finally {
		server.close();
	}
});

void test("a refused connection is unreachable, named by its code and never by its address", async () => {
	const port = await closedPort();
	const probe = await probeEndpoint(`http://127.0.0.1:${String(port)}`, "k", FAST);
	assert.deepEqual(probe, { reachable: false, reason: "ECONNREFUSED" });
});

void test("a connection that never completes, or an answer that never comes, stops at its bound", async () => {
	// A plain TCP server never completes a TLS handshake, so the connection never finishes.
	const silent = createTcpServer();
	const port = await listening(silent);
	try {
		const started = Date.now();
		assert.deepEqual(await probeEndpoint(`https://127.0.0.1:${String(port)}`, "k", FAST), {
			reachable: false,
			reason: "connect-timeout",
		});
		// Two attempts of half a second each, not the 30-second timeout that the composer pays.
		assert.ok(Date.now() - started < 5000);
		assert.deepEqual(await probeEndpoint(`http://127.0.0.1:${String(port)}`, "k", FAST), {
			reachable: false,
			reason: "response-timeout",
		});
	} finally {
		silent.close();
	}
});

void test("a retry that answers makes the endpoint reachable", async () => {
	let requests = 0;
	const server = createHttpServer((_request, response) => {
		requests += 1;
		if (requests === 1) {
			// The first request gets no answer, so it times out.
			return;
		}
		response.writeHead(200).end("{}");
	});
	const port = await listening(server);
	try {
		assert.deepEqual(await probeEndpoint(`http://127.0.0.1:${String(port)}`, "k", FAST), {
			reachable: true,
			status: 200,
		});
		assert.equal(requests, 2);
	} finally {
		server.closeAllConnections();
		server.close();
	}
});

void test("an unreachable endpoint fails the step with a named reason, and no output holds the URL or the key", async () => {
	const port = await closedPort();
	const directory = mkdtempSync(path.join(tmpdir(), "endpoint-probe-"));
	const output = path.join(directory, "output");
	const summary = path.join(directory, "summary");
	const url = `http://127.0.0.1:${String(port)}`;
	const key = "secret-key-value";
	const run = spawnSync(process.execPath, [PROBE, "--title", "Feedback eval panel"], {
		encoding: "utf8",
		env: {
			...process.env,
			HEPHAESTUS_LIVE_LLM_BASE_URL: url,
			HEPHAESTUS_LIVE_LLM_API_KEY: key,
			GITHUB_OUTPUT: output,
			GITHUB_STEP_SUMMARY: summary,
		},
	});
	const printed = `${run.stdout}${run.stderr}`;
	assert.equal(run.status, 1, printed);
	assert.equal(readFileSync(output, "utf8"), "reachable=false\n");
	const written = readFileSync(summary, "utf8");
	assert.match(
		printed,
		/::error title=Feedback eval panel::The model endpoint is unreachable \(ECONNREFUSED\)/u,
	);
	assert.match(
		written,
		/The model endpoint is unreachable \(ECONNREFUSED\), so nothing was composed\./u,
	);
	for (const text of [printed, written]) {
		assert.doesNotMatch(text, /127\.0\.0\.1/u);
		assert.ok(!text.includes(String(port)), "the port leaked");
		assert.ok(!text.includes(key), "the key leaked");
	}
});
