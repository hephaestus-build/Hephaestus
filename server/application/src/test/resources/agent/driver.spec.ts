// The driver pulls its subject's runs, keeps each lease while the kind works, and ends each run itself.

import assert from "node:assert/strict";
import { once } from "node:events";
import { syncBuiltinESMExports } from "node:module";
import test from "node:test";
import { setImmediate, setTimeout as sleep } from "node:timers/promises";

import type { Isolation } from "../../../main/resources/agent/driver-isolation.ts";
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

const ISOLATED: Promise<Isolation> = Promise.resolve({ isolated: true });

interface Call {
	path: string;
	headers: Record<string, string>;
	body: string | undefined;
}

/** How the fake gateway answers the n-th renewal: a status, a network failure, or no answer until the call aborts. */
type Renewal = number | "fail" | "hang";

interface Gateway {
	/** The runs that `next` offers, one after another; then no run. */
	runs: object[];
	renewal?: (count: number) => Renewal;
	/** How many `next` calls fail with 503 before the first answers. */
	failingNexts?: number;
	/** The lease's end that each answer names, from now. */
	leaseMs?: number;
}

/** A gateway that serves one sandbox, and records every call it gets. */
function gateway({ runs, renewal = () => 200, failingNexts = 0, leaseMs = 60_000 }: Gateway) {
	const calls: Call[] = [];
	let renewals = 0;
	let nexts = 0;
	const renewed = new EventTarget();
	const fetch: typeof globalThis.fetch = async (input, init) => {
		const path = url(input).slice(GATEWAY.length);
		calls.push({
			path,
			headers: Object.fromEntries(Object.entries(init?.headers ?? {})),
			body: typeof init?.body === "string" ? init.body : undefined,
		});
		if (path === "/internal/agent/next") {
			nexts += 1;
			if (nexts <= failingNexts) {
				return new Response(null, { status: 503 });
			}
			const run = runs.shift();
			return run === undefined ? new Response(null, { status: 204 }) : Response.json(run);
		}
		if (path.endsWith("/lease")) {
			renewals += 1;
			renewed.dispatchEvent(new Event("renewed"));
			const answer = renewal(renewals);
			if (answer === "fail") {
				throw new TypeError("fetch failed");
			}
			if (answer === "hang") {
				const signal = init?.signal ?? new AbortController().signal;
				await once(signal, "abort");
				throw new DOMException("aborted", "AbortError");
			}
			return answer === 200
				? Response.json({ leasedUntil: new Date(Date.now() + leaseMs).toISOString() })
				: new Response(null, { status: answer });
		}
		return new Response(null, { status: 204 });
	};
	return { calls, fetch, renewed };
}

const assignment = (kind: string, { leaseMs = 60_000, deadline = null as string | null } = {}) => ({
	runId: RUN,
	epoch: 3,
	kind,
	leasedUntil: new Date(Date.now() + leaseMs).toISOString(),
	deadline,
});

/** Drives until the gateway has offered every run once and answered one idle `next`. */
async function driveOnce(
	kinds: Record<string, KindRunner>,
	fake: ReturnType<typeof gateway>,
	renewEveryMs = 5,
	isolation = ISOLATED,
) {
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
		isolation,
	});
	return fake.calls;
}

const ends = (calls: Call[]) =>
	calls.filter((call) => call.path.endsWith("/end")).map((call) => call.body);

/** A kind that waits for its signal and reports that its lease was lost. */
const untilAborted: KindRunner = async ({ signal }) => {
	await once(signal, "abort");
	return { leaseLost: true };
};

void test("a run is handed to its kind with its claim's headers, and the driver ends it", async () => {
	let seen: Parameters<KindRunner>[0] | undefined;
	const calls = await driveOnce(
		{
			REVIEW_COMPOSE: async (run) => {
				seen = run;
				return { leaseLost: false, outcome: "completed" };
			},
		},
		gateway({ runs: [assignment("REVIEW_COMPOSE")] }),
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
	const fake = gateway({ runs: [assignment("REVIEW_COMPOSE")] });
	await driveOnce(
		{
			REVIEW_COMPOSE: async () => {
				// The kind works until the lease was renewed twice.
				await once(fake.renewed, "renewed");
				await once(fake.renewed, "renewed");
				return { leaseLost: false, outcome: "completed" };
			},
		},
		fake,
	);

	assert.ok(fake.calls.filter((call) => call.path.endsWith("/lease")).length >= 2);
	assert.deepEqual(ends(fake.calls), [JSON.stringify({ outcome: "completed" })]);
});

for (const status of [409, 404, 401]) {
	void test(`a renewal answered ${String(status)} aborts the kind, and the driver sends no end`, async () => {
		const calls = await driveOnce(
			{ REVIEW_COMPOSE: untilAborted },
			gateway({ runs: [assignment("REVIEW_COMPOSE")], renewal: () => status }),
		);

		assert.deepEqual(ends(calls), []);
	});
}

for (const failure of ["fail", "hang"] as const) {
	void test(`renewals that ${failure} lose the lease at its end, and the driver sends no end`, async () => {
		const started = Date.now();
		const calls = await driveOnce(
			{ REVIEW_COMPOSE: untilAborted },
			// The driver counts the lease as lost two seconds before its end on the gateway's clock.
			gateway({
				runs: [assignment("REVIEW_COMPOSE", { leaseMs: 2300 })],
				renewal: () => failure,
			}),
			50,
		);

		assert.deepEqual(ends(calls), []);
		assert.ok(Date.now() - started < 5000);
	});
}

void test("a server roll cannot spend the remaining lease on one failed renewal", async (t) => {
	t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: Date.now() });
	// The driver imports the promise timer as an ESM binding.
	syncBuiltinESMExports();
	t.after(() => {
		t.mock.timers.reset();
		syncBuiltinESMExports();
	});
	// AbortSignal uses a platform timer, which must share the fake clock with the driver.
	t.mock.method(AbortSignal, "timeout", (milliseconds: number) => {
		const controller = new AbortController();
		// oxlint-disable-next-line no-restricted-globals -- fake timers are on (t.mock.timers); this moves AbortSignal.timeout onto the test clock
		setTimeout(() => controller.abort(new DOMException("timed out", "TimeoutError")), milliseconds);
		return controller.signal;
	});
	const fake = gateway({
		runs: [assignment("TURN")],
		renewal: (count) => (count === 1 ? "hang" : 200),
	});
	const stop = new AbortController();
	const finished = Promise.withResolvers<undefined>();
	let aborted = false;
	const fetch: typeof globalThis.fetch = async (input, init) => {
		const response = await fake.fetch(input, init);
		if (url(input).endsWith("/lease")) {
			// The replacement is healthy, but a response still needs time to cross the network.
			await sleep(500, undefined, { signal: init?.signal ?? undefined });
			finished.resolve(undefined);
		}
		if (url(input).endsWith("/next") && response.status === 204) {
			stop.abort();
		}
		return response;
	};
	const running = drive({
		gatewayUrl: GATEWAY,
		token: "sandbox-token",
		kinds: {
			TURN: async ({ signal }) => {
				await Promise.race([finished.promise, once(signal, "abort")]);
				aborted = signal.aborted;
				return aborted ? { leaseLost: true } : { leaseLost: false, outcome: "completed" };
			},
		},
		signal: stop.signal,
		fetch,
		log: quiet,
		isolation: ISOLATED,
	});
	try {
		for (let second = 0; second < 60; second += 1) {
			await setImmediate();
			t.mock.timers.tick(1000);
			await setImmediate();
		}
		assert.equal(aborted, false);
		assert.deepEqual(ends(fake.calls), [JSON.stringify({ outcome: "completed" })]);
	} finally {
		stop.abort();
		await running;
	}
});

void test("the run's deadline stops the kind, and the driver leaves the run to the server", async () => {
	const deadline = new Date(Date.now() + 100).toISOString();
	let aborted = false;
	const calls = await driveOnce(
		{
			REVIEW_COMPOSE: async ({ signal }) => {
				await once(signal, "abort");
				aborted = true;
				return { leaseLost: false, outcome: "completed" };
			},
		},
		gateway({ runs: [assignment("REVIEW_COMPOSE", { deadline })] }),
		1000,
	);

	assert.equal(aborted, true);
	assert.deepEqual(ends(calls), []);
});

void test("a run of a kind that this sandbox does not run, or whose kind fails, is handed on", async () => {
	const calls = await driveOnce(
		{
			TURN: async () => {
				throw new Error("the model broke");
			},
		},
		gateway({ runs: [assignment("REVIEW_COMPOSE"), assignment("TURN")] }),
	);

	assert.deepEqual(ends(calls), [
		JSON.stringify({ outcome: "handed_on" }),
		JSON.stringify({ outcome: "handed_on" }),
	]);
});

void test("a run that finishes as the sandbox stops is still ended, and the driver pulls no more", async () => {
	const fake = gateway({ runs: [assignment("REVIEW_COMPOSE"), assignment("REVIEW_COMPOSE")] });
	const stop = new AbortController();
	let aborted = false;
	await drive({
		gatewayUrl: GATEWAY,
		token: "sandbox-token",
		kinds: {
			REVIEW_COMPOSE: async ({ signal }) => {
				stop.abort();
				aborted = signal.aborted;
				return { leaseLost: false, outcome: "completed" };
			},
		},
		signal: stop.signal,
		fetch: fake.fetch,
		renewEveryMs: 5,
		log: quiet,
		isolation: ISOLATED,
	});

	assert.equal(aborted, true);
	assert.deepEqual(ends(fake.calls), [JSON.stringify({ outcome: "completed" })]);
	assert.equal(fake.calls.filter((call) => call.path.endsWith("/next")).length, 1);
});

void test("the driver pulls again after the gateway failed to answer next", async () => {
	const calls = await driveOnce(
		{ TURN: async () => ({ leaseLost: false, outcome: "completed" }) },
		gateway({ runs: [assignment("TURN")], failingNexts: 2 }),
	);

	assert.deepEqual(ends(calls), [JSON.stringify({ outcome: "completed" })]);
});

void test("an assignment that does not name a run, its claim and its lease is no run to start", () => {
	assert.deepEqual(parseAssignment(assignment("TURN")).deadline, null);
	for (const broken of [
		null,
		{ ...assignment("TURN"), runId: "../etc" },
		{ ...assignment("TURN"), runId: "-".repeat(36) },
		{ ...assignment("TURN"), epoch: 0 },
		{ ...assignment("TURN"), kind: "" },
		{ ...assignment("TURN"), leasedUntil: "soon" },
		{ ...assignment("TURN"), leasedUntil: "1" },
		{ ...assignment("TURN"), deadline: "never" },
	]) {
		assert.throws(() => parseAssignment(broken), /assignment/u);
	}
});

void test("no kind starts before the sandbox's policy holds", async () => {
	const events: string[] = [];
	const { promise: isolation, resolve } = Promise.withResolvers<Isolation>();
	// oxlint-disable-next-line no-restricted-globals -- isolation must arrive after the driver is ready to start, which is the order under test
	setTimeout(() => {
		events.push("isolated");
		resolve({ isolated: true });
	}, 30);
	await driveOnce(
		{
			REVIEW_COMPOSE: async () => {
				events.push("kind");
				return { leaseLost: false, outcome: "completed" };
			},
		},
		gateway({ runs: [assignment("REVIEW_COMPOSE")] }),
		5,
		isolation,
	);

	assert.deepEqual(events, ["isolated", "kind"]);
});

void test("a sandbox whose policy did not hold runs no kind, ends its run as unisolated, and pulls no more", async () => {
	let ran = false;
	const fake = gateway({ runs: [assignment("REVIEW_COMPOSE"), assignment("REVIEW_COMPOSE")] });
	await drive({
		gatewayUrl: GATEWAY,
		token: "sandbox-token",
		kinds: {
			REVIEW_COMPOSE: async () => {
				ran = true;
				return { leaseLost: false, outcome: "completed" };
			},
		},
		signal: new AbortController().signal,
		fetch: fake.fetch,
		renewEveryMs: 5,
		log: quiet,
		isolation: Promise.resolve({
			isolated: false,
			reason: "the Kubernetes API still answered after the bound",
		}),
	});

	assert.equal(ran, false);
	assert.deepEqual(ends(fake.calls), [JSON.stringify({ outcome: "unisolated" })]);
	assert.equal(fake.calls.filter((call) => call.path.endsWith("/next")).length, 1);
});
