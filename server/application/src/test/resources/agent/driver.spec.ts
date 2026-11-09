// The driver pulls its subject's runs, keeps each lease while the kind works, and ends each run itself.

import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { setTimeout as sleep } from "node:timers/promises";

import { drive, parseAssignment, type KindRunner } from "../../../main/resources/agent/driver.ts";

const RUN = "0b1d4b71-5f1e-4a52-9a43-7e0c4b0d8a11";
const GATEWAY = "http://10.43.0.12:8081";

/** The URL of a request: the driver sends a string. */
function url(input: Parameters<typeof globalThis.fetch>[0]): string {
	if (typeof input === "string") {
		return input;
	}
	return input instanceof URL ? input.href : input.url;
}

const quiet = (_line: string) => undefined;

interface Call {
	path: string;
	headers: Record<string, string>;
	body: string | undefined;
}

/** A gateway that offers `assignments` one after another, then no run, and answers each lease with `lease`. */
function gateway(assignments: object[], lease: (count: number) => number = () => 200) {
	const calls: Call[] = [];
	let renewals = 0;
	const fetch: typeof globalThis.fetch = async (input, init) => {
		const path = url(input).slice(GATEWAY.length);
		calls.push({
			path,
			headers: Object.fromEntries(Object.entries(init?.headers ?? {})),
			body: typeof init?.body === "string" ? init.body : undefined,
		});
		if (path === "/internal/agent/next") {
			const assignment = assignments.shift();
			return assignment === undefined
				? new Response(null, { status: 204 })
				: Response.json(assignment);
		}
		if (path.endsWith("/lease")) {
			renewals += 1;
			const status = lease(renewals);
			return status === 200
				? Response.json({ leasedUntil: new Date(Date.now() + 60_000).toISOString() })
				: new Response(null, { status });
		}
		return new Response(null, { status: 204 });
	};
	return { calls, fetch };
}

const assignment = (kind: string) => ({
	runId: RUN,
	epoch: 3,
	kind,
	leasedUntil: new Date(Date.now() + 60_000).toISOString(),
	deadline: null,
});

/** Drives until the gateway has offered every run once and answered one idle `next`. */
async function driveOnce(
	kinds: Record<string, KindRunner>,
	pending: object[],
	lease?: (count: number) => number,
	renewEveryMs = 5,
) {
	const fake = gateway(pending, lease);
	const stop = new AbortController();
	const idle: typeof globalThis.fetch = async (input, init) => {
		const response = await fake.fetch(input, init);
		if (response.status === 204 && url(input).endsWith("/next")) {
			stop.abort();
		}
		return response;
	};
	await drive({
		gatewayUrl: GATEWAY,
		token: "sandbox-token",
		kinds,
		signal: stop.signal,
		fetch: idle,
		renewEveryMs,
		idleWaitMs: 1,
		log: quiet,
	});
	return fake.calls;
}

void test("a run is handed to its kind with its claim's headers, and the driver ends it", async () => {
	let seen: Parameters<KindRunner>[0] | undefined;
	const calls = await driveOnce(
		{
			REVIEW_COMPOSE: async (run) => {
				seen = run;
				return { leaseLost: false, outcome: "completed" };
			},
		},
		[assignment("REVIEW_COMPOSE")],
	);

	assert.ok(seen !== undefined);
	assert.equal(seen.assignment.runId, RUN);
	assert.equal(seen.assignment.epoch, 3);
	assert.deepEqual(seen.gateway, {
		url: GATEWAY,
		headers: { authorization: "Bearer sandbox-token", "x-agent-run": RUN, "x-agent-epoch": "3" },
	});
	const end = calls.find((call) => call.path === `/internal/agent/runs/${RUN}/end`);
	assert.ok(end !== undefined);
	assert.equal(end.body, JSON.stringify({ outcome: "completed" }));
	assert.equal(end.headers["x-agent-epoch"], "3");
});

void test("the driver keeps the lease while the kind works", async () => {
	const calls = await driveOnce(
		{
			REVIEW_COMPOSE: async () => {
				await sleep(40);
				return { leaseLost: false, outcome: "completed" };
			},
		},
		[assignment("REVIEW_COMPOSE")],
	);

	assert.ok(calls.filter((call) => call.path.endsWith("/lease")).length >= 2);
});

void test("a refused renewal aborts the kind, and the driver sends no end", async () => {
	let aborted = false;
	const calls = await driveOnce(
		{
			REVIEW_COMPOSE: async ({ signal }) => {
				await once(signal, "abort");
				aborted = signal.aborted;
				return { leaseLost: true };
			},
		},
		[assignment("REVIEW_COMPOSE")],
		() => 409,
	);

	assert.equal(aborted, true);
	assert.equal(
		calls.some((call) => call.path.endsWith("/end")),
		false,
	);
});

void test("a run of a kind that this sandbox does not run, or whose kind fails, is handed on", async () => {
	const calls = await driveOnce(
		{
			TURN: async () => {
				throw new Error("the model broke");
			},
		},
		[assignment("REVIEW_COMPOSE"), assignment("TURN")],
	);

	assert.deepEqual(
		calls.filter((call) => call.path.endsWith("/end")).map((call) => call.body),
		[JSON.stringify({ outcome: "handed_on" }), JSON.stringify({ outcome: "handed_on" })],
	);
});

void test("a stop during a run aborts the kind and ends nothing", async () => {
	const fake = gateway([assignment("REVIEW_COMPOSE")]);
	const stop = new AbortController();
	await drive({
		gatewayUrl: GATEWAY,
		token: "sandbox-token",
		kinds: {
			REVIEW_COMPOSE: async ({ signal }) => {
				stop.abort();
				assert.equal(signal.aborted, true);
				return { leaseLost: false, outcome: "completed" };
			},
		},
		signal: stop.signal,
		fetch: fake.fetch,
		renewEveryMs: 5,
		log: quiet,
	});

	assert.equal(
		fake.calls.some((call) => call.path.endsWith("/end")),
		false,
	);
});

void test("an assignment that does not name a run, its claim and its lease is no run to start", () => {
	assert.deepEqual(parseAssignment(assignment("TURN")).deadline, null);
	for (const broken of [
		null,
		{ ...assignment("TURN"), runId: "../etc" },
		{ ...assignment("TURN"), epoch: 0 },
		{ ...assignment("TURN"), kind: "" },
		{ ...assignment("TURN"), leasedUntil: "soon" },
		{ ...assignment("TURN"), deadline: "never" },
	]) {
		assert.throws(() => parseAssignment(broken), /assignment/u);
	}
});
