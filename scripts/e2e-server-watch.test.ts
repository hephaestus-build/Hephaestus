import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, type Server, type ServerResponse } from "node:http";
import { after, test } from "node:test";

import { hangReport, probePage, watch } from "./e2e-server-watch.ts";

function port(listening: Server): number {
	const address = listening.address();
	assert.ok(address !== null && typeof address === "object", "the server listens on a port");
	return address.port;
}

// Answers until `hang` is set, then holds every request open: a server whose request threads are all stuck.
const state = { hang: false };
const held: ServerResponse[] = [];
const server = createServer((_request, response) => {
	if (state.hang) {
		held.push(response);
		return;
	}
	response.end("<!doctype html><title>Sign in</title>");
});
server.listen(0, "127.0.0.1");
await once(server, "listening");
const url = `http://127.0.0.1:${port(server)}/login`;
after(() => {
	for (const response of held) {
		response.destroy();
	}
	server.closeAllConnections();
	server.close();
});

void test("a server that answers is watched until it ends, and nothing is reported", async () => {
	let probes = 0;
	const verdict = await watch({
		probe: async () => {
			probes += 1;
			return probePage(url, 1000);
		},
		alive: () => probes < 3,
		intervalMs: 1,
		misses: 1,
	});
	assert.deepEqual(verdict, { kind: "ended" });
	assert.equal(probes, 3);
});

void test("a server that stops answering is reported after the misses in a row, with when it began", async () => {
	state.hang = true;
	try {
		const times = [0, 1, 2, 3].map((second) => new Date(Date.UTC(2026, 10, 24, 15, 28, second)));
		let tick = 0;
		const verdict = await watch({
			probe: async () => probePage(url, 100),
			alive: () => true,
			intervalMs: 1,
			misses: 3,
			now: () => {
				tick += 1;
				return times[tick - 1] ?? new Date(0);
			},
		});
		if (verdict.kind !== "hung") {
			assert.fail(`expected a hang, got ${verdict.kind}`);
		}
		assert.equal(verdict.misses.length, 3);
		assert.match(verdict.misses[0]?.detail ?? "", /TimeoutError/u);
		const report = hangReport(url, verdict.misses, [
			"Every thread, virtual threads included: /x.json",
		]);
		assert.match(
			report,
			/stopped answering http:\/\/127\.0\.0\.1:\d+\/login: 3 probes in a row had no answer, from 2026-11-24T15:28:00\.000Z/u,
		);
		assert.match(report, /\nEvery thread, virtual threads included: \/x\.json\n/u);
	} finally {
		state.hang = false;
	}
});

void test("one slow answer between answers is not a hang", async () => {
	const answers = [undefined, "TimeoutError", undefined, "TimeoutError", "TimeoutError", undefined];
	let probes = 0;
	const verdict = await watch({
		probe: async () => {
			probes += 1;
			return answers[probes - 1];
		},
		alive: () => probes < answers.length,
		intervalMs: 1,
		misses: 3,
	});
	assert.deepEqual(verdict, { kind: "ended" });
});

void test("a server that ends while a probe waits is an ending, not a hang", async () => {
	let running = true;
	const verdict = await watch({
		probe: async () => {
			running = false;
			return "TypeError: fetch failed: connect ECONNREFUSED";
		},
		alive: () => running,
		intervalMs: 1,
		misses: 1,
	});
	assert.deepEqual(verdict, { kind: "ended" });
});

void test("an error answer is still an answer", async () => {
	const failing = createServer((_request, response) => {
		response.statusCode = 500;
		response.end("broken");
	});
	failing.listen(0, "127.0.0.1");
	await once(failing, "listening");
	try {
		assert.equal(await probePage(`http://127.0.0.1:${port(failing)}/`, 1000), undefined);
	} finally {
		failing.close();
	}
});
