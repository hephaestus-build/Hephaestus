import assert from "node:assert/strict";
import { once } from "node:events";
import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import test from "node:test";
import timers, { setTimeout as sleep } from "node:timers/promises";

import {
	ModelRuntime,
	type SessionEntry,
	type SessionMessageEntry,
} from "@earendil-works/pi-coding-agent";

import {
	CONTEXT_FOLDER_TOOL_NAMES,
	HEPH_TOOL_NAMES,
	isRecord,
	type SandboxExecAnswer,
	type SandboxReadAnswer,
	type TurnContext,
	type TurnEnd,
} from "../../../main/resources/agent/conversation-loop-protocol.ts";
import {
	breakpointBeforeSituation,
	createConversationLoop,
	failureSummary,
	httpTurnApi,
	type LinkAnswer,
	type ModelFactory,
	partialStream,
	proxyModels,
	THINKING_STAGE,
	tooLarge,
	turnStage,
	type TurnApi,
	WorkerStatusError,
} from "../../../main/resources/agent/conversation-loop.ts";
import {
	AGENT_DIR,
	contextOf,
	CWD,
	fakeWorker,
	type FakeWorker,
	hostFor,
	isSessionEntry,
	ran,
	runTurn,
	SITUATION,
} from "./conversation-loop-worker.ts";

// The faux provider ships in pi-ai, which the root package reaches only through pi-coding-agent.
const piAi: unknown = await import(
	new URL("../../pi-ai/dist/index.js", import.meta.resolve("@earendil-works/pi-coding-agent")).href
);

interface FauxRequest {
	messages: unknown[];
}

type FauxStep = (context: FauxRequest, options?: { reasoning?: string }) => unknown;

interface FauxHandle {
	provider: Parameters<ModelRuntime["registerNativeProvider"]>[0];
	getModel: () => { id: string };
	appendResponses: (steps: FauxStep[]) => void;
}

interface FauxModule {
	fauxProvider: (options: {
		provider: string;
		tokensPerSecond?: number;
		models?: { id: string; reasoning?: boolean; contextWindow?: number; maxTokens?: number }[];
	}) => FauxHandle;
	fauxAssistantMessage: (
		content: unknown,
		options?: { stopReason?: string; errorMessage?: string },
	) => unknown;
	fauxText: (text: string) => unknown;
	fauxToolCall: (name: string, args: Record<string, unknown>) => unknown;
}

function isFauxModule(value: unknown): value is FauxModule {
	return (
		typeof value === "object" &&
		value !== null &&
		"fauxProvider" in value &&
		typeof value.fauxProvider === "function" &&
		"fauxAssistantMessage" in value &&
		typeof value.fauxAssistantMessage === "function"
	);
}

if (!isFauxModule(piAi)) {
	throw new Error("pi-ai ships no faux provider");
}
const faux = piAi;

function textOf(message: unknown): string {
	if (!isRecord(message)) {
		return "";
	}
	const { content } = message;
	if (typeof content === "string") {
		return content;
	}
	return Array.isArray(content)
		? content
				.map((block: unknown) =>
					isRecord(block) && typeof block.text === "string" ? block.text : "",
				)
				.join("")
		: "";
}

function roleOf(message: unknown): string {
	return isRecord(message) && typeof message.role === "string" ? message.role : "";
}

function idOf(entry: unknown): string {
	return isRecord(entry) && typeof entry.id === "string" ? entry.id : "";
}

/** A faux model whose answers a test scripts per request, streamed at `tokensPerSecond`. */
function fauxModels(
	steps: FauxStep[],
	tokensPerSecond = 100_000,
	reasoning = false,
	limits?: { contextWindow: number; maxTokens: number },
): ModelFactory {
	const provider = `faux-${crypto.randomUUID()}`;
	const handle = faux.fauxProvider({
		provider,
		tokensPerSecond,
		...(reasoning || limits ? { models: [{ id: "faux-model", reasoning, ...limits }] } : {}),
	});
	handle.appendResponses(steps);
	return async () => {
		const modelRuntime = await ModelRuntime.create({
			authPath: `${AGENT_DIR}/auth.json`,
			modelsPath: null,
			allowModelNetwork: false,
		});
		modelRuntime.registerNativeProvider(handle.provider);
		const model = modelRuntime.getModel(provider, handle.getModel().id);
		if (model === undefined) {
			throw new Error("faux model missing");
		}
		return { modelRuntime, model };
	};
}

function messages(entries: readonly SessionEntry[], role: string): SessionMessageEntry[] {
	return entries.filter(
		(entry): entry is SessionMessageEntry =>
			entry.type === "message" && entry.message.role === role,
	);
}

void test("a turn sends every entry Pi appended after the stored path once, with its end, the question first and the answer last", async () => {
	const worker = fakeWorker(contextOf([], "[Thu 2026-10-08 09:12 UTC · app] hello"));

	const end = await runTurn(worker, fauxModels([() => faux.fauxAssistantMessage("first answer")]));

	assert.equal(end.outcome, "DONE");
	assert.equal(end.entries.length, worker.appended.length);
	const [question] = messages(worker.appended, "user");
	const answer = messages(worker.appended, "assistant").at(-1);
	assert.equal(textOf(question?.message), "[Thu 2026-10-08 09:12 UTC · app] hello");
	assert.equal(textOf(answer?.message), "first answer");
	assert.ok(
		question !== undefined &&
			answer !== undefined &&
			worker.appended.indexOf(question) < worker.appended.indexOf(answer),
	);

	// The next turn starts from the stored ledger and appends after it, without repeating it.
	const following = fakeWorker(contextOf(worker.ledger(), "again"));
	const next = await runTurn(
		following,
		fauxModels([() => faux.fauxAssistantMessage("second answer")]),
	);
	assert.equal(next.outcome, "DONE");
	const stored = new Set(worker.ledger().map(idOf));
	assert.ok(following.appended.every((entry) => !stored.has(entry.id)));
	assert.deepEqual(
		messages(following.appended, "user").map((entry) => textOf(entry.message)),
		["again"],
	);
});

void test("a turn that keeps calling its model stops at the call limit and fails as too long", async () => {
	const worker = fakeWorker(contextOf([], "Why is this item not met?"));
	let requests = 0;
	const looping = Array.from({ length: 60 }, () => () => {
		requests += 1;
		return faux.fauxAssistantMessage([faux.fauxToolCall("read", { path: "INDEX.md" })], {
			stopReason: "toolUse",
		});
	});

	const end = await runTurn(worker, fauxModels(looping));

	assert.equal(end.outcome, "FAILED");
	assert.equal(end.reason, "DEADLINE");
	assert.equal(end.detail, "call_limit");
	assert.equal(requests, 40);
});

void test("a turn whose answer comes with its last allowed model call ends as written", async () => {
	const worker = fakeWorker(contextOf([], "Why is this item not met?"));
	const steps = [
		...Array.from(
			{ length: 39 },
			() => () =>
				faux.fauxAssistantMessage([faux.fauxToolCall("read", { path: "INDEX.md" })], {
					stopReason: "toolUse",
				}),
		),
		() => faux.fauxAssistantMessage("Here is why."),
	];

	const end = await runTurn(worker, fauxModels(steps));

	assert.equal(end.outcome, "DONE");
});

void test("a model call that fails at the call limit keeps its own reason", async () => {
	const worker = fakeWorker(contextOf([], "Why is this item not met?"));
	const steps = [
		...Array.from(
			{ length: 39 },
			() => () =>
				faux.fauxAssistantMessage([faux.fauxToolCall("read", { path: "INDEX.md" })], {
					stopReason: "toolUse",
				}),
		),
		() => faux.fauxAssistantMessage("", { stopReason: "error" }),
	];

	const end = await runTurn(worker, fauxModels(steps));

	assert.equal(end.reason, "ERROR");
	assert.equal(end.detail, "model_error");
});

void test("a turn whose model fails ends failed, sends none of its entries, and tells the worker why", async () => {
	const worker = fakeWorker(contextOf([], "question"));

	const end = await runTurn(
		worker,
		fauxModels([() => faux.fauxAssistantMessage("", { stopReason: "error" })]),
	);

	assert.deepEqual(end, {
		outcome: "FAILED",
		entries: [],
		reason: "ERROR",
		detail: "model_error",
	});
});

void test("a failed turn names its kind of failure to the worker and in its log, never the provider's words", async (t) => {
	const canary = "sk-canary-7Qx9";
	const quoted = "my private question about the outage";
	const written: string[] = [];
	t.mock.method(process.stderr, "write", (chunk: unknown) => {
		written.push(String(chunk));
		return true;
	});
	const worker = fakeWorker(contextOf([], quoted));

	const end = await runTurn(
		worker,
		fauxModels([
			() =>
				faux.fauxAssistantMessage("", {
					stopReason: "error",
					errorMessage: `Incorrect API key provided: ${canary} for "${quoted}"`,
				}),
		]),
	);
	t.mock.restoreAll();

	assert.deepEqual(end, { outcome: "FAILED", entries: [], reason: "ERROR", detail: "model_error" });
	const log = written.join("");
	assert.match(log, /failed \(ERROR\): model_error/u);
	assert.ok(!log.includes(canary) && !log.includes(quoted), log);
});

void test("a failure in the host's log is its kind and the worker's status, whatever its message writes", () => {
	// A multi-line message puts a line that reads as a stack frame ahead of the real frames.
	const forged = "search failed with 500:\n    at sk-canary-7Qx9 private question";
	const answered = failureSummary(new WorkerStatusError(forged, 500));
	const refused = failureSummary(new WorkerStatusError(forged, 422));
	const thrown = failureSummary(new Error(forged));

	assert.equal(answered, "worker_unavailable status=500");
	assert.equal(refused, "worker_refused status=422");
	assert.equal(thrown, "host_error");
	for (const summary of [answered, refused, thrown]) {
		assert.ok(!summary.includes("sk-canary") && !summary.includes("private question"), summary);
	}
	assert.equal(failureSummary("text"), "host_error");
});

void test("a model call that Pi retries leaves its cut-off text out of the end and the partial answer", async () => {
	const worker = fakeWorker(contextOf([], "question"));

	const end = await runTurn(
		worker,
		fauxModels(
			[
				() =>
					faux.fauxAssistantMessage("Here is the first half of my ans", {
						stopReason: "error",
						errorMessage: "terminated",
					}),
				() => faux.fauxAssistantMessage(`Here is the whole answer. ${"More of it. ".repeat(20)}`),
			],
			// Slow enough that the partial answer is reported while the retried answer streams.
			500,
		),
	);

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(
		messages(worker.appended, "assistant").map((entry) => textOf(entry.message)),
		[`Here is the whole answer. ${"More of it. ".repeat(20)}`],
	);
	assert.ok(worker.appended.every((entry) => entry.type !== "context_edit"));
	const answering = worker.partials.filter((snapshot) =>
		JSON.stringify(snapshot).includes("whole answer"),
	);
	assert.ok(answering.length > 0);
	assert.ok(answering.every((snapshot) => !JSON.stringify(snapshot).includes("first half")));
});

void test("the situation reaches every model request last and is never stored", async () => {
	const requests: FauxRequest[] = [];
	// The tool returns once the worker heard that it runs, so the test sees that partial.
	const toolReported = Promise.withResolvers<undefined>();
	const worker = fakeWorker(
		contextOf([], "question"),
		async (request) => {
			await toolReported.promise;
			return { ok: true, text: request.path };
		},
		(snapshot) => {
			if (snapshot.activity === "tool" && snapshot.tool === "read") {
				toolReported.resolve(undefined);
			}
		},
	);
	const models = fauxModels([
		(context) => {
			requests.push(context);
			return faux.fauxAssistantMessage(
				[
					faux.fauxText("let me look"),
					faux.fauxToolCall("read", { path: "inputs/context/user.json" }),
				],
				{ stopReason: "toolUse" },
			);
		},
		(context) => {
			requests.push(context);
			return faux.fauxAssistantMessage("done");
		},
	]);

	const end = await runTurn(worker, models);

	assert.equal(end.outcome, "DONE");
	assert.equal(requests.length, 2);
	for (const request of requests) {
		assert.ok(textOf(request.messages.at(-1)).startsWith("<situation>"));
	}
	assert.equal(JSON.stringify(worker.appended).includes("<situation>"), false);
	assert.deepEqual(worker.reads, [{ path: "inputs/context/user.json" }]);
	// The answer in progress keeps what Hephaestus wrote before the tool, though nothing is stored until the end. It carries
	// the text and nothing else the worker does not read.
	const tool = worker.partials.find(
		(partial) => partial.activity === "tool" && partial.tool === "read",
	);
	assert.deepEqual(tool?.messages, [{ content: [{ type: "text", text: "let me look" }] }]);
});

void test("text before a tool call stays in its message, and a message without a tool call ends the turn as written", async () => {
	// The host shows what the model wrote and adds nothing: a turn that ends on a sentence about a next read ended
	// because the model stopped there, not because the host cut it off (system.md § Your context has the rule).
	let requests = 0;
	const worker = fakeWorker(contextOf([], "How am I doing on describe-why?"));
	const end = await runTurn(
		worker,
		fauxModels([
			() => {
				requests += 1;
				return faux.fauxAssistantMessage(
					[
						faux.fauxText("Your standing is Needs attention."),
						faux.fauxToolCall("read", { path: "INDEX.md" }),
					],
					{ stopReason: "toolUse" },
				);
			},
			() => {
				requests += 1;
				return faux.fauxAssistantMessage("I need to read the merge readiness details.");
			},
			() => {
				requests += 1;
				return faux.fauxAssistantMessage("never asked for");
			},
		]),
	);

	assert.equal(end.outcome, "DONE");
	assert.equal(requests, 2);
	assert.deepEqual(
		messages(worker.appended, "assistant").map((entry) =>
			entry.message.role === "assistant"
				? entry.message.content.map((block) => (block.type === "text" ? block.text : block.type))
				: [],
		),
		[
			["Your standing is Needs attention.", "toolCall"],
			["I need to read the merge readiness details."],
		],
	);
});

void test("a slow partial answer write holds back only the newest snapshot, and a stop drops it", async () => {
	const intervalMs = 5;
	const writes: { tool: string | null; done: PromiseWithResolvers<undefined> }[] = [];
	let inFlight = 0;
	let mostInFlight = 0;
	const api: TurnApi = {
		...fakeWorker(contextOf([], "question")).api(),
		partial: async (snapshot) => {
			const done = Promise.withResolvers<undefined>();
			writes.push({ tool: snapshot.tool, done });
			inFlight += 1;
			mostInFlight = Math.max(mostInFlight, inFlight);
			await done.promise;
			inFlight -= 1;
		},
	};
	const partials = partialStream(api, intervalMs);
	const startTool = (toolName: string) => {
		partials.observe({ type: "tool_execution_start", toolCallId: toolName, toolName, args: {} });
	};
	const sent = () => writes.map((write) => write.tool);
	const release = (index: number) => writes[index]?.done.resolve(undefined);
	const windows = async () => sleep(intervalMs * 10);

	startTool("first");
	await windows();
	for (const toolName of ["second", "third", "fourth"]) {
		startTool(toolName);
		await windows();
	}
	assert.deepEqual(sent(), ["first"]);

	release(0);
	await windows();
	assert.deepEqual(sent(), ["first", "fourth"]);
	release(1);
	await windows();
	assert.deepEqual(sent(), ["first", "fourth"]);

	startTool("fifth");
	await windows();
	startTool("sixth");
	await windows();
	partials.stop();
	const settled = (async () => {
		await partials.settled();
		return "settled";
	})();
	release(2);
	assert.equal(await Promise.race([settled, sleep(1000, "waiting")]), "settled");
	await windows();
	assert.deepEqual(sent(), ["first", "fourth", "fifth"]);
	assert.equal(mostInFlight, 1);
});

void test("partial answers keep their interval when a write returns before the next window", async (t) => {
	t.mock.timers.enable({ apis: ["setTimeout", "Date"] });
	const intervalMs = 50;
	const started: number[] = [];
	const api: TurnApi = {
		...fakeWorker(contextOf([], "question")).api(),
		partial: async () => {
			started.push(Date.now());
			// Through the module object, which the mock patches; the named import keeps the real timer.
			await timers.setTimeout(5);
		},
	};
	const partials = partialStream(api, intervalMs);
	for (let update = 0; update < (intervalMs * 8) / 2; update += 1) {
		partials.observe({
			type: "tool_execution_start",
			toolCallId: `tool-${update}`,
			toolName: "read",
			args: {},
		});
		t.mock.timers.tick(2);
		// The promise chains that a fired timer started run before time moves on.
		await timers.setImmediate();
	}
	partials.stop();
	t.mock.timers.tick(intervalMs);
	await timers.setImmediate();
	await partials.settled();

	assert.ok(started.length >= 3, `only ${started.length} writes`);
	const gaps = started.slice(1).map((at, index) => at - (started[index] ?? at));
	// A write right after the previous one returned would start 5 ms later.
	assert.ok(
		gaps.every((gap) => gap >= intervalMs),
		`writes ${gaps.join(", ")} ms apart`,
	);
});

void test("search_conversation asks the worker in the shape the worker binds", async () => {
	// HephTurnControllerIntegrationTest posts the same file to the real endpoint.
	const query: unknown = JSON.parse(
		readFileSync(new URL("heph-turn-contract/search.request.json", import.meta.url), "utf8"),
	);
	assert.ok(isRecord(query));
	const worker = fakeWorker(contextOf([], "what did we decide?"));

	const end = await runTurn(
		worker,
		fauxModels([
			() =>
				faux.fauxAssistantMessage([faux.fauxToolCall("search_conversation", query)], {
					stopReason: "toolUse",
				}),
			() => faux.fauxAssistantMessage("found nothing"),
		]),
	);

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(worker.searched, [query]);
});

/** Whether a model request offered a tool, as its definition or as one Pi added in a system message. */
function offers(request: FauxRequest | undefined, tool: string): boolean {
	return JSON.stringify(request ?? null).includes(`"name":"${tool}"`);
}

void test("Hephaestus has read, bash and load_context only in a turn whose context says the worker serves them", async () => {
	for (const contextFolder of [false, true]) {
		const requests: FauxRequest[] = [];
		const worker = fakeWorker({ ...contextOf([], "question"), contextFolder });
		await runTurn(
			worker,
			fauxModels([
				(context) => {
					requests.push(context);
					return faux.fauxAssistantMessage("answer");
				},
			]),
		);
		for (const tool of CONTEXT_FOLDER_TOOL_NAMES) {
			assert.equal(offers(requests[0], tool), contextFolder, tool);
		}
		assert.ok(offers(requests[0], "search_conversation"));
	}

	// A worker that does not name the field serves no context folder.
	const served: unknown = JSON.parse(
		readFileSync(new URL("heph-turn-contract/context.response.json", import.meta.url), "utf8"),
	);
	assert.ok(isRecord(served));
	const { contextFolder: _contextFolder, ...withoutFolder } = served;
	const server = createServer((_request, response) => {
		response.writeHead(200, { "content-type": "application/json" });
		response.end(JSON.stringify(withoutFolder));
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	try {
		const address = server.address();
		assert.ok(isRecord(address) && typeof address.port === "number");
		const context = await httpTurnApi(
			`http://127.0.0.1:${address.port}`,
			"turn-1",
			"token",
		).context();
		assert.equal(context.contextFolder, false);
	} finally {
		server.close();
	}
});

void test("a turn without a context folder never reaches the worker for files and still ends with its answer", async () => {
	const worker = fakeWorker({
		...contextOf([], "Why is my pull request not met?"),
		contextFolder: false,
	});
	const requests: FauxRequest[] = [];
	const end = await runTurn(
		worker,
		fauxModels([
			(context) => {
				requests.push(context);
				return faux.fauxAssistantMessage([faux.fauxToolCall("read", { path: "INDEX.md" })], {
					stopReason: "toolUse",
				});
			},
			(context) => {
				requests.push(context);
				return faux.fauxAssistantMessage("I can't look at your pull requests right now.");
			},
		]),
	);

	assert.equal(end.outcome, "DONE");
	assert.equal(requests.length, 2);
	assert.deepEqual([worker.reads.length, worker.execs.length, worker.loads.length], [0, 0, 0]);
});

function contract(name: string): Record<string, unknown> {
	const value: unknown = JSON.parse(
		readFileSync(new URL(`heph-turn-contract/${name}`, import.meta.url), "utf8"),
	);
	assert.ok(isRecord(value));
	return value;
}

/** The tool results the model read in a request, with their error flags. */
function resultsIn(request: FauxRequest | undefined): { text: string; isError: unknown }[] {
	return (request?.messages ?? [])
		.filter((message) => roleOf(message) === "toolResult")
		.map((message) => ({ text: textOf(message), isError: isRecord(message) && message.isError }));
}

void test("read, bash and load_context ask the worker in the shape the worker binds and show its answers", async () => {
	// HephTurnGatewayIntegrationTest sends the same files to the real endpoints.
	const read = contract("sandbox-read.request.json");
	const exec = contract("sandbox-exec.request.json");
	const load = contract("sandbox-load.request.json");
	const { timeoutMs } = exec;
	assert.ok(typeof timeoutMs === "number");
	const requests: FauxRequest[] = [];
	const worker = fakeWorker(contextOf([], "what changed in pull request 42?"));

	const end = await runTurn(
		worker,
		fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[
						faux.fauxToolCall("read", read),
						faux.fauxToolCall("bash", { command: exec.command, timeout: timeoutMs / 1000 }),
						faux.fauxToolCall("load_context", load),
					],
					{ stopReason: "toolUse" },
				),
			(context) => {
				requests.push(context);
				return faux.fauxAssistantMessage("it widened to long");
			},
		]),
	);

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(worker.reads, [read]);
	assert.deepEqual(worker.execs, [exec]);
	assert.deepEqual(worker.loads, [load]);
	assert.deepEqual(resultsIn(requests[0]), [
		{ text: `contents of ${String(read.path)}`, isError: false },
		{ text: `ran ${String(exec.command)}`, isError: false },
		{
			text:
				"Loaded repos/github.com/acme/api at pull/42 (7044a8a0c0ffee0c0ffee0c0ffee0c0ffee0c0ff). Cite its code " +
				"as `<file>:<line>@7044a8a`.\nStaged pull request #42 of acme/api with its history.",
			isError: false,
		},
	]);
});

void test("a page and an output the worker cut reach the model as the worker wrote them", async () => {
	const page = `${"line\n".repeat(3)}\n[Showing lines 1-2000 of 5000. Use offset=2001 to continue.]`;
	const tail = `${"commit\n".repeat(3)}\n[Showing lines 3001-5000 of 5000. Full output: /tmp/heph-out/1.log]`;
	const requests: FauxRequest[] = [];
	const worker = fakeWorker(contextOf([], "question"), async () => ({ ok: true, text: page }));
	const api = worker.api();
	const host = createConversationLoop({
		cwd: CWD,
		agentDir: AGENT_DIR,
		turnApi: () => ({
			...api,
			exec: async (request) => {
				worker.execs.push(request);
				return {
					ok: true,
					exitCode: 0,
					output: tail,
					truncated: true,
					timedOut: false,
					fullOutputPath: "/tmp/heph-out/1.log",
				};
			},
		}),
		models: fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[
						faux.fauxToolCall("read", { path: "inputs/context/activity.json" }),
						faux.fauxToolCall("bash", { command: "git log" }),
					],
					{ stopReason: "toolUse" },
				),
			(context) => {
				requests.push(context);
				return faux.fauxAssistantMessage("done");
			},
		]),
	});
	void host.start(crypto.randomUUID(), 1, "turn-token");
	const end = await worker.waitForEnd();
	await host.idle();

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(worker.execs, [{ command: "git log" }], "no timeout asked, none sent");
	assert.deepEqual(resultsIn(requests[0]), [
		{ text: page, isError: false },
		{ text: tail, isError: false },
	]);
});

void test("a failed, timed out or silent command reads as Pi's bash words it, and a refusal is the worker's text", async () => {
	const requests: FauxRequest[] = [];
	const worker = fakeWorker(contextOf([], "question"), async (request) => ({
		ok: false,
		error: "NOT_FOUND",
		detail: `File not found: ${request.path}`,
	}));
	const answers: SandboxExecAnswer[] = [
		ran("fatal: bad revision 'nope'", 128),
		{ ...ran("partial", null), timedOut: true },
		ran(""),
		{
			ok: false,
			error: "UNAVAILABLE",
			detail: "This worker cannot run turn sandboxes now. Do not retry.",
		},
	];
	const api = worker.api();
	const host = createConversationLoop({
		cwd: CWD,
		agentDir: AGENT_DIR,
		turnApi: () => ({
			...api,
			exec: async (request) => {
				worker.execs.push(request);
				return answers.shift() ?? ran("unexpected");
			},
			load: async (request) => {
				worker.loads.push(request);
				return {
					ok: false,
					error: "NOT_VISIBLE",
					detail: "Hephaestus cannot read repos/github.com/other/x.",
				};
			},
		}),
		models: fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[
						faux.fauxToolCall("bash", { command: "git show nope" }),
						faux.fauxToolCall("bash", { command: "sleep 90", timeout: 5 }),
						faux.fauxToolCall("bash", { command: "true", timeout: 600 }),
						faux.fauxToolCall("bash", { command: "ls" }),
						faux.fauxToolCall("bash", { command: "ls", timeout: -1 }),
						faux.fauxToolCall("read", { path: "inputs/context/nothing.json" }),
						faux.fauxToolCall("load_context", { path: "repos/github.com/other/x" }),
					],
					{ stopReason: "toolUse" },
				),
			(context) => {
				requests.push(context);
				return faux.fauxAssistantMessage("I could not read it");
			},
		]),
	});
	void host.start(crypto.randomUUID(), 1, "turn-token");
	const end = await worker.waitForEnd();
	await host.idle();

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(
		worker.execs.map((request) => request.timeoutMs),
		[undefined, 5000, 60_000, undefined],
		"a timeout past the worker's bound is sent as the bound, an invalid one not at all",
	);
	assert.deepEqual(resultsIn(requests[0]), [
		{ text: "fatal: bad revision 'nope'\n\nCommand exited with code 128", isError: true },
		{ text: "partial\n\nCommand timed out after 5 seconds", isError: true },
		{ text: "(no output)", isError: false },
		{ text: "This worker cannot run turn sandboxes now. Do not retry.", isError: true },
		{ text: "Invalid timeout: must be a finite number of seconds", isError: true },
		{ text: "File not found: inputs/context/nothing.json", isError: true },
		{ text: "NOT_VISIBLE: Hephaestus cannot read repos/github.com/other/x.", isError: true },
	]);
});

void test("after a completed turn only its own read, bash and load_context results stay verbatim", async () => {
	const firstCalls = [
		faux.fauxToolCall("read", { path: "inputs/context/user.json" }),
		faux.fauxToolCall("bash", { command: "git -C repos/github.com/acme/api log -n 5" }),
		faux.fauxToolCall("load_context", { path: "repos/github.com/acme/api@pull/42" }),
		faux.fauxToolCall("search_conversation", { query: "pin" }),
	];
	const contextTurn = async (calls: unknown[], entries: unknown[], prompt: string) => {
		const worker = fakeWorker(contextOf(entries, prompt));
		const end = await runTurn(
			worker,
			fauxModels([
				() => faux.fauxAssistantMessage(calls, { stopReason: "toolUse" }),
				() => faux.fauxAssistantMessage(`answered ${prompt}`),
			]),
		);
		assert.equal(end.outcome, "DONE");
		return worker;
	};
	const first = await contextTurn(firstCalls, [], "one");
	const second = await contextTurn(
		[faux.fauxToolCall("read", { path: "inputs/context/workspace.json" })],
		first.ledger(),
		"two",
	);

	const firstResults = messages(first.appended, "toolResult");
	const edits = second.appended.flatMap((entry) => (entry.type === "context_edit" ? [entry] : []));
	assert.deepEqual(
		edits.map((entry) => entry.targetId),
		firstResults.slice(0, 3).map((entry) => entry.id),
		"search_conversation keeps its result",
	);
	const at = String.raw`(?:Sun|Mon|Tue|Wed|Thu|Fri|Sat) \d{4}-\d{2}-\d{2} \d{2}:\d{2} UTC`;

	// The third turn's model sees the first turn's results as notes and the second's verbatim.
	const requests: FauxRequest[] = [];
	const third = fakeWorker(contextOf(second.ledger(), "three"));
	const end = await runTurn(
		third,
		fauxModels([
			(context) => {
				requests.push(context);
				return faux.fauxAssistantMessage("three");
			},
		]),
	);
	assert.equal(end.outcome, "DONE");
	const results = resultsIn(requests[0]).map((result) => result.text);
	assert.equal(results.length, 5);
	assert.match(
		results[0] ?? "",
		new RegExp(String.raw`^\[read inputs/context/user\.json, read at ${at}\. .*read it again`, "u"),
	);
	assert.match(
		results[1] ?? "",
		new RegExp(
			String.raw`^\[bash ${"`"}git -C repos/github\.com/acme/api log -n 5${"`"}, run at ${at}\. .*run it again`,
			"u",
		),
	);
	assert.match(
		results[2] ?? "",
		new RegExp(
			String.raw`^\[load_context repos/github\.com/acme/api@pull/42, loaded at ${at}\. .*load it again`,
			"u",
		),
	);
	assert.equal(results[3], JSON.stringify({ hits: [] }));
	assert.equal(results[4], "contents of inputs/context/workspace.json");
	assert.ok(
		results.every((text) => !text.includes("ran git")),
		"no output survives in a note",
	);
});

const LINK_CALLS = [
	[
		"link_result",
		{ resultId: "0b6c1f0e-8d3a-4c51-9a4e-2f7d5c9e1a33", text: "The review found the why missing." },
	],
	[
		"link_problem",
		{
			problemId: "6f2a9d14-3c7b-4e85-a1d0-9b8e2c4f7a16",
			text: "This one is the missing rollback.",
		},
	],
	[
		"show_feedback",
		{ feedbackId: "c3e8a5b2-71d4-4f9a-8e60-2d5b9f1c4a87", text: "Name why, not only what." },
	],
] as const;

const SHOWN = {
	link_result: "Shown to the developer: feedback on result 0b6c1f0e-8d3a-4c51-9a4e-2f7d5c9e1a33",
	link_problem: "Shown to the developer: feedback on problem 6f2a9d14-3c7b-4e85-a1d0-9b8e2c4f7a16",
	show_feedback:
		"Shown to the developer: feedback on prepared feedback c3e8a5b2-71d4-4f9a-8e60-2d5b9f1c4a87",
} as const;

/** A model that calls every tool that shows feedback in one message, and then answers. */
function linkingModels(): ModelFactory {
	return fauxModels([
		() =>
			faux.fauxAssistantMessage(
				[
					faux.fauxText("Here is what I see."),
					...LINK_CALLS.map(([name, args]) => faux.fauxToolCall(name, { ...args })),
				],
				{ stopReason: "toolUse" },
			),
		() => faux.fauxAssistantMessage("What do you make of it?"),
	]);
}

function toolResults(worker: FakeWorker): { name: string; text: string; isError: boolean }[] {
	return messages(worker.appended, "toolResult").flatMap((entry) =>
		entry.message.role === "toolResult"
			? [
					{
						name: entry.message.toolName,
						text: textOf(entry.message),
						isError: entry.message.isError,
					},
				]
			: [],
	);
}

void test("Hephaestus has the tools to read its context folder, show feedback and search, and nothing else", () => {
	assert.deepEqual([...CONTEXT_FOLDER_TOOL_NAMES], ["read", "bash", "load_context"]);
	assert.deepEqual(
		[...HEPH_TOOL_NAMES],
		["link_result", "link_problem", "show_feedback", "search_conversation"],
	);
});

void test("a tool that shows feedback asks the worker to admit its id and then says what was shown", async () => {
	const worker = fakeWorker(contextOf([], "what did the review say?"));

	const end = await runTurn(worker, linkingModels());

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(
		worker.linked,
		LINK_CALLS.map(([name, args]) => [name, Object.values(args)[0]]),
	);
	assert.deepEqual(
		toolResults(worker),
		LINK_CALLS.map(([name]) => ({ name, text: SHOWN[name], isError: false })),
	);
});

void test("a tool whose id the worker refuses fails, and nothing is shown", async () => {
	const worker = fakeWorker(
		contextOf([], "what did the review say?"),
		undefined,
		undefined,
		async (tool) =>
			tool === "show_feedback"
				? { admitted: false, reason: "That feedback was already shown elsewhere." }
				: { admitted: false, reason: null },
	);

	const end = await runTurn(worker, linkingModels());

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(toolResults(worker), [
		{
			name: "link_result",
			text: "link_result: nothing was shown, the server did not admit result 0b6c1f0e-8d3a-4c51-9a4e-2f7d5c9e1a33",
			isError: true,
		},
		{
			name: "link_problem",
			text: "link_problem: nothing was shown, the server did not admit problem 6f2a9d14-3c7b-4e85-a1d0-9b8e2c4f7a16",
			isError: true,
		},
		{
			name: "show_feedback",
			text:
				"show_feedback: nothing was shown, the server did not admit prepared feedback " +
				"c3e8a5b2-71d4-4f9a-8e60-2d5b9f1c4a87: That feedback was already shown elsewhere.",
			isError: true,
		},
	]);
});

void test("a tool that shows feedback in a stopped turn says nothing was shown", async () => {
	const linking = Promise.withResolvers<undefined>();
	const admit = Promise.withResolvers<LinkAnswer>();
	const worker = fakeWorker(contextOf([], "question"), undefined, undefined, async () => {
		linking.resolve(undefined);
		return admit.promise;
	});
	const host = hostFor(worker, linkingModels());
	const turnId = crypto.randomUUID();
	void host.start(turnId, 1, "turn-token");
	await linking.promise;
	host.stop(turnId, 1, "developer");
	admit.resolve({ admitted: true });
	const end = await worker.waitForEnd();
	await host.idle();

	assert.equal(end.outcome, "STOPPED");
	const [first] = toolResults(worker);
	assert.deepEqual(first, {
		name: "link_result",
		text: "link_result: the turn was stopped, so nothing was shown",
		isError: true,
	});
	assert.ok(
		toolResults(worker).every((result) => !result.text.startsWith("Shown to the developer")),
	);
});

void test("the partial answer carries the calls that show feedback, with their ids and words", async () => {
	const toolReported = Promise.withResolvers<undefined>();
	const worker = fakeWorker(
		contextOf([], "what did the review say?"),
		undefined,
		(snapshot) => {
			if (snapshot.activity === "tool") {
				toolReported.resolve(undefined);
			}
		},
		async () => {
			await toolReported.promise;
			return { admitted: true };
		},
	);

	const end = await runTurn(worker, linkingModels());

	assert.equal(end.outcome, "DONE");
	const tool = worker.partials.find((partial) => partial.activity === "tool");
	const [first] = tool?.messages ?? [];
	assert.ok(first !== undefined);
	assert.deepEqual(
		first.content.map((block) =>
			block.type === "text" ? block : { name: block.name, arguments: block.arguments },
		),
		[
			{ type: "text", text: "Here is what I see." },
			...LINK_CALLS.map(([name, args]) => ({ name, arguments: args })),
		],
	);
	assert.ok(
		first.content.every((block) => block.type === "text" || block.id !== ""),
		"every call keeps its id",
	);
});

const NO_USAGE = {
	input: 0,
	output: 0,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: 0,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

/** One stored question and answer. `usage` is what the provider reported for the answer. */
function storedExchange(index: number, parentId: string | null, usage = NO_USAGE): unknown[] {
	const question = crypto.randomUUID().slice(0, 8);
	const answer = crypto.randomUUID().slice(0, 8);
	const timestamp = new Date(0).toISOString();
	return [
		{
			type: "message",
			id: question,
			parentId,
			timestamp,
			message: { role: "user", content: `question ${index} ${"q".repeat(4500)}`, timestamp: 0 },
		},
		{
			type: "message",
			id: answer,
			parentId: question,
			timestamp,
			message: {
				role: "assistant",
				content: [{ type: "text", text: `answer ${index} ${"a".repeat(4500)}` }],
				api: "openai-completions",
				provider: "faux",
				model: "faux",
				usage,
				stopReason: "stop",
				timestamp: 0,
			},
		},
	];
}

/** Whether a model request is one of Pi's summary requests rather than a turn of the conversation. */
function asksForSummary(request: FauxRequest): boolean {
	const text = request.messages.map(textOf).join("\n");
	return text.includes("<conversation>") || text.includes("# Conversation");
}

/**
 * A stored conversation past the working trigger. The last answer reports 40,000 tokens, below the working trigger
 * (MENTOR_WORKING_TOKENS). A question stored after it, from a turn that failed, adds about 10,000 more by Pi's
 * estimate of one token per four characters. Pi's own check before a prompt reads only the last answer's usage; the
 * host's counts both and compacts.
 */
function pastTheTrigger(): unknown[] {
	const earlier: unknown[] = [];
	for (let index = 0; index < 25; index += 1) {
		const parent = idOf(earlier.at(-1));
		const tokens = index === 24 ? 40_000 : 0;
		earlier.push(
			...storedExchange(index, parent === "" ? null : parent, {
				...NO_USAGE,
				input: tokens,
				totalTokens: tokens,
			}),
		);
	}
	earlier.push({
		type: "message",
		id: crypto.randomUUID().slice(0, 8),
		parentId: idOf(earlier.at(-1)),
		timestamp: new Date(0).toISOString(),
		message: { role: "user", content: `unanswered ${"u".repeat(40_000)}`, timestamp: 0 },
	});
	return earlier;
}

void test("a conversation past the working budget is compacted before the prompt, and the end carries the summary before the question", async () => {
	const earlier = pastTheTrigger();
	const answered: FauxRequest[] = [];
	// Pi may summarise a split turn's prefix in a call of its own, so every step answers what it is asked.
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		answered.push(request);
		return faux.fauxAssistantMessage("the answer");
	};
	const worker = fakeWorker(contextOf(earlier, "the question"));

	const end = await runTurn(worker, fauxModels([step, step, step]));

	assert.equal(end.outcome, "DONE");
	const summary = worker.appended.findIndex((entry) => entry.type === "compaction");
	const question = worker.appended.findIndex(
		(entry) => entry.type === "message" && textOf(entry.message) === "the question",
	);
	const answer = worker.appended.findIndex(
		(entry) => entry.type === "message" && textOf(entry.message) === "the answer",
	);
	assert.notEqual(summary, -1, "the conversation was compacted");
	assert.ok(summary < question, "the summary precedes the question");
	assert.ok(question < answer, "the answer follows the question");
	// The question was answered from the summary, not from the conversation it replaced.
	assert.equal(answered.length, 1);
	const sent = answered[0]?.messages.map(textOf).join("\n") ?? "";
	assert.match(sent, /the summary/u);
	assert.doesNotMatch(sent, /question 0 /u);
});

/** A file of about 10,500 tokens by Pi's estimate. */
function largeFile(name: string): string {
	return `${name} ${"f".repeat(42_000)}`;
}

void test("a turn that passes the working budget with its answer and link is not summarised before it ends", async () => {
	// The shape of staging's turn 115fee59 (T2-2): two reads, then the whole answer with a link_result call, which
	// passed the trigger. Pi then summarised the older conversation inside the turn, and the continuation commented on
	// the answer. About 40,000 tokens of earlier conversation by Pi's estimate.
	const earlier: unknown[] = [];
	for (let index = 0; index < 18; index += 1) {
		const parent = idOf(earlier.at(-1));
		earlier.push(...storedExchange(index, parent === "" ? null : parent));
	}
	const requests: FauxRequest[] = [];
	const step =
		(answer: () => ReturnType<typeof faux.fauxAssistantMessage>): FauxStep =>
		(request) => {
			if (asksForSummary(request)) {
				return faux.fauxAssistantMessage("## Goal\nthe summary");
			}
			requests.push(request);
			return answer();
		};
	// About 5,000 tokens each: the turn stays inside Pi's recent messages, so Pi's cut falls in the older conversation.
	const worker = fakeWorker(
		contextOf(earlier, "what feedback did I get recently?"),
		async (request) => ({
			ok: true,
			text: `${request.path} ${"r".repeat(20_000)}`,
		}),
	);

	const end = await runTurn(
		worker,
		fauxModels([
			step(() =>
				faux.fauxAssistantMessage(
					[
						faux.fauxToolCall("read", {
							path: "inputs/context/prepared_conversation_feedback.json",
						}),
						faux.fauxToolCall("read", { path: "inputs/context/results_history.json" }),
					],
					{ stopReason: "toolUse" },
				),
			),
			step(() =>
				faux.fauxAssistantMessage(
					[
						faux.fauxText("The review found five problems; the insecure file access matters most."),
						faux.fauxToolCall("link_result", { ...LINK_CALLS[0][1] }),
					],
					{ stopReason: "toolUse" },
				),
			),
			step(() => faux.fauxAssistantMessage("Want the details?")),
			step(() => faux.fauxAssistantMessage("unused")),
		]),
	);

	assert.equal(end.outcome, "DONE");
	assert.equal(requests.length, 3);
	assert.ok(
		worker.appended.every((entry) => entry.type !== "compaction"),
		"no summary was written inside the turn",
	);
	const last = requests.at(-1)?.messages.map(textOf).join("\n") ?? "";
	assert.ok(
		!last.includes("the summary"),
		"the continuation does not read a summary of the conversation",
	);
	assert.ok(last.includes("question 0 "), "the continuation still reads the earlier conversation");
});

void test("a turn whose reads pass the working budget answers from the files it read, not from a summary of them", async () => {
	// About 27,000 tokens by Pi's estimate of one token per four characters, below the working trigger.
	const earlier: unknown[] = [];
	for (let index = 0; index < 12; index += 1) {
		const parent = idOf(earlier.at(-1));
		earlier.push(...storedExchange(index, parent === "" ? null : parent));
	}
	// Each read is about 10,500 tokens: the third passes the trigger, and keeping the recent messages would cut the
	// turn after its first read.
	const read = (path: string) => (_request: FauxRequest) =>
		faux.fauxAssistantMessage([faux.fauxToolCall("read", { path })], { stopReason: "toolUse" });
	const answered: FauxRequest[] = [];
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		answered.push(request);
		return faux.fauxAssistantMessage("the answer");
	};
	const worker = fakeWorker(contextOf(earlier, "walk me through the change"), async (request) => ({
		ok: true,
		text: largeFile(request.path),
	}));

	const end = await runTurn(
		worker,
		fauxModels([read("the-diff"), read("the-old-file"), read("the-new-file"), step, step, step]),
	);

	assert.equal(end.outcome, "DONE");
	assert.equal(answered.length, 1);
	const sent = answered[0]?.messages.map(textOf).join("\n") ?? "";
	assert.ok(
		sent.includes(largeFile("the-diff")),
		"the first file the turn read reaches the answer whole",
	);
	assert.ok(!sent.includes("the summary"), "the answer does not rest on a summary of this turn");
});

void test("a summary is written with thinking off and the answer with the configured effort, and no level is stored", async () => {
	const levels: { summary: (string | undefined)[]; answer: (string | undefined)[] } = {
		summary: [],
		answer: [],
	};
	const step: FauxStep = (request, options) => {
		if (asksForSummary(request)) {
			levels.summary.push(options?.reasoning);
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		levels.answer.push(options?.reasoning);
		return faux.fauxAssistantMessage("the answer");
	};
	const context = contextOf(pastTheTrigger(), "the question");
	context.model.reasoningEffort = "HIGH";
	const worker = fakeWorker(context);

	const end = await runTurn(worker, fauxModels([step, step, step], 100_000, true));

	assert.equal(end.outcome, "DONE");
	assert.ok(levels.summary.length > 0, "the conversation was compacted");
	assert.deepEqual(new Set(levels.summary), new Set([undefined]));
	assert.deepEqual(levels.answer, ["high"]);
	assert.equal(
		worker.appended.some(
			(entry) => entry.type === "thinking_level_change" && entry.thinkingLevel === "off",
		),
		false,
		"the summary's level is not stored in the conversation",
	);
});

function longPrompt(context: TurnContext): TurnContext {
	return { ...context, systemPrompt: `You are Hephaestus. ${"p".repeat(24_000)}` };
}

void test("a conversation on the smallest window Hephaestus takes is shortened, keeps its summary and answers every later turn", async () => {
	// A 32,768-token model with a fixed prompt of about 6,000 tokens. The newest answer alone is about 17,000 tokens,
	// so Pi keeps it whole and the shortened conversation stays past a fixed working budget; the model still has room.
	const earlier: unknown[] = [];
	for (let index = 0; index < 6; index += 1) {
		const parent = idOf(earlier.at(-1));
		earlier.push(...storedExchange(index, parent === "" ? null : parent));
	}
	const long = storedExchange(6, idOf(earlier.at(-1)), {
		...NO_USAGE,
		input: 31_000,
		totalTokens: 31_000,
	});
	const longAnswer = long[1];
	if (isRecord(longAnswer) && isRecord(longAnswer.message)) {
		longAnswer.message.content = [{ type: "text", text: `answer 6 ${"a".repeat(68_000)}` }];
	}
	earlier.push(...long);
	const summaries: FauxRequest[] = [];
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			summaries.push(request);
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		return faux.fauxAssistantMessage("the answer");
	};
	const limits = { contextWindow: 32_768, maxTokens: 8192 };
	const runAt32k = async (worker: FakeWorker) => {
		const host = createConversationLoop({
			cwd: CWD,
			agentDir: AGENT_DIR,
			turnApi: () => worker.api(),
			models: fauxModels([step, step, step, step], 100_000, false, limits),
			partialIntervalMs: 10,
		});
		void host.start(crypto.randomUUID(), 1, "turn-token");
		const end = await worker.waitForEnd();
		await host.idle();
		return end;
	};

	const first = fakeWorker(longPrompt(contextOf(earlier, "the question")));
	const end = await runAt32k(first);

	assert.equal(end.outcome, "DONE", end.detail ?? "");
	assert.ok(summaries.length > 0, "the conversation was summarised");
	assert.ok(
		first.appended.some((entry) => entry.type === "compaction"),
		"the summary is stored",
	);
	for (const question of ["the next question", "and one more"]) {
		const next = fakeWorker(longPrompt(contextOf(first.ledger(), question)));
		const later = await runAt32k(next);
		assert.equal(later.outcome, "DONE", later.detail ?? "");
		first.appended.push(...next.appended);
	}
});

void test("a summary written before a turn fails is kept, so the next turn starts from it", async () => {
	let summaries = 0;
	let failAnswer = true;
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			summaries += 1;
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		return faux.fauxAssistantMessage(failAnswer ? "  " : "the answer");
	};
	const worker = fakeWorker(contextOf(pastTheTrigger(), "the question"));

	const end = await runTurn(worker, fauxModels([step, step, step]));

	assert.equal(end.outcome, "FAILED");
	assert.deepEqual(
		end.entries.map((entry) => (isRecord(entry) ? entry.type : "")),
		["compaction"],
		"only the summary is stored",
	);
	const written = summaries;

	failAnswer = false;
	const next = fakeWorker(contextOf(worker.ledger(), "the question again"));
	const later = await runTurn(next, fauxModels([step, step, step]));

	assert.equal(later.outcome, "DONE");
	assert.equal(summaries, written, "the next turn does not summarise again");
});

void test("a summary that fails leaves the history whole and is answered, and the next turn compacts again", async () => {
	let failSummaries = true;
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			return failSummaries
				? faux.fauxAssistantMessage("## Goal\nthe sum", { stopReason: "length" })
				: faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		return faux.fauxAssistantMessage("the answer");
	};
	const worker = fakeWorker(contextOf(pastTheTrigger(), "the question"));

	const end = await runTurn(worker, fauxModels([step, step, step, step, step]));

	assert.equal(end.outcome, "DONE");
	assert.equal(worker.appended.filter((entry) => entry.type === "compaction").length, 0);
	assert.ok(
		worker.appended.some(
			(entry) => entry.type === "message" && textOf(entry.message) === "the answer",
		),
	);

	failSummaries = false;
	const next = fakeWorker(contextOf(worker.ledger(), "the next question"));
	const later = await runTurn(next, fauxModels([step, step, step, step, step]));

	assert.equal(later.outcome, "DONE");
	assert.ok(
		next.appended.some((entry) => entry.type === "compaction"),
		"the next turn compacts the conversation",
	);
});

void test("a conversation over the working budget with nothing older to summarise is answered, not failed", async () => {
	// One short exchange whose answer reports more than the working trigger: Pi keeps it whole as recent and has
	// nothing to compact. Each later question would meet the same history, so the turn must go ahead.
	const earlier = storedExchange(0, null, { ...NO_USAGE, input: 60_000, totalTokens: 60_000 });
	const answered: FauxRequest[] = [];
	const worker = fakeWorker(contextOf(earlier, "the question"));

	const end = await runTurn(
		worker,
		fauxModels([
			(request) => {
				answered.push(request);
				return faux.fauxAssistantMessage("the answer");
			},
		]),
	);

	assert.equal(end.outcome, "DONE");
	assert.equal(answered.length, 1);
	assert.equal(worker.appended.filter((entry) => entry.type === "compaction").length, 0);
	assert.ok(
		worker.appended.some(
			(entry) => entry.type === "message" && textOf(entry.message) === "the answer",
		),
		"the answer is stored",
	);
});

void test("a conversation past the model's window that cannot get shorter fails without asking the model", async () => {
	// Asked anyway, the model would answer and Pi would read the answer as an overflow and leave it out, so the turn
	// would end done with nothing to show.
	const earlier = storedExchange(0, null, { ...NO_USAGE, input: 200_000, totalTokens: 200_000 });
	const answered: FauxRequest[] = [];
	const worker = fakeWorker(contextOf(earlier, "the question"));

	const end = await runTurn(
		worker,
		fauxModels([
			(request) => {
				answered.push(request);
				return faux.fauxAssistantMessage("the answer");
			},
		]),
	);

	assert.equal(end.outcome, "FAILED");
	assert.equal(end.detail, "context_overflow");
	assert.equal(answered.length, 0);
});

void test("an answer with no words fails the turn instead of ending it done with nothing to show", async () => {
	const worker = fakeWorker(contextOf([], "the question"));

	const end = await runTurn(worker, fauxModels([() => faux.fauxAssistantMessage("  ")]));

	assert.equal(end.outcome, "FAILED");
	assert.equal(end.reason, "ERROR");
});

/** A model that answers with `first`, then ends with an empty message, as a model may after a tool. */
function thenEmpty(...first: FauxStep[]): ModelFactory {
	return fauxModels([...first, () => faux.fauxAssistantMessage("")]);
}

function linkCall(text: string) {
	return faux.fauxToolCall("link_result", { ...LINK_CALLS[0][1], text });
}

/** What the developer reads of the stored turn: its words and the text of each link call. */
function shownText(worker: FakeWorker): string[] {
	const shown: string[] = [];
	for (const entry of messages(worker.appended, "assistant")) {
		if (entry.message.role !== "assistant") {
			continue;
		}
		for (const part of entry.message.content) {
			if (part.type === "text" && part.text !== "") {
				shown.push(part.text);
			} else if (part.type === "toolCall" && typeof part.arguments.text === "string") {
				shown.push(part.arguments.text);
			}
		}
	}
	return shown;
}

void test("an answer that was whole with the feedback it links ends done with nothing more to say", async () => {
	const worker = fakeWorker(contextOf([], "what did the review say?"));

	const end = await runTurn(
		worker,
		thenEmpty(() =>
			faux.fauxAssistantMessage(
				[
					faux.fauxText("The review found the why missing on #46."),
					linkCall("The why is missing."),
				],
				{ stopReason: "toolUse" },
			),
		),
	);

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(shownText(worker), [
		"The review found the why missing on #46.",
		"The why is missing.",
	]);
});

void test("an answer told only in the text of the feedback it links, or in a link batch after it, ends done", async () => {
	for (const answer of [
		[
			() =>
				faux.fauxAssistantMessage([linkCall("The why is missing on #46.")], {
					stopReason: "toolUse",
				}),
		],
		[
			() =>
				faux.fauxAssistantMessage(
					[
						faux.fauxText("The review found the why missing on #46."),
						faux.fauxToolCall("link_problem", { ...LINK_CALLS[1][1] }),
					],
					{ stopReason: "toolUse" },
				),
			() => faux.fauxAssistantMessage([linkCall("The why is missing.")], { stopReason: "toolUse" }),
		],
	] satisfies FauxStep[][]) {
		const worker = fakeWorker(contextOf([], "what did the review say?"));

		const end = await runTurn(worker, thenEmpty(...answer));

		assert.equal(end.outcome, "DONE");
		assert.ok(
			shownText(worker).some((text) => text.includes("#46")),
			shownText(worker).join(" | "),
		);
	}
});

void test("a turn that announced a read, or whose answer was a link the worker refused, still fails when it ends empty", async () => {
	const announced = fakeWorker(contextOf([], "what did the review say?"));
	const refused = fakeWorker(
		contextOf([], "what did the review say?"),
		undefined,
		undefined,
		async () => ({ admitted: false, reason: null }),
	);

	const ends = [
		await runTurn(
			announced,
			thenEmpty(() =>
				faux.fauxAssistantMessage(
					[
						faux.fauxText("Let me look."),
						faux.fauxToolCall("read", { path: "inputs/context/user.json" }),
					],
					{ stopReason: "toolUse" },
				),
			),
		),
		await runTurn(
			refused,
			thenEmpty(() =>
				faux.fauxAssistantMessage(
					[faux.fauxText("Here is what the review found:"), linkCall("The why is missing on #46.")],
					{ stopReason: "toolUse" },
				),
			),
		),
	];

	for (const end of ends) {
		assert.equal(end.outcome, "FAILED");
		assert.equal(end.reason, "ERROR");
	}
});

void test("an answer of only chat-template control tokens fails the turn and stores no answer", async () => {
	const worker = fakeWorker(contextOf([], "the question"));

	const end = await runTurn(
		worker,
		fauxModels([() => faux.fauxAssistantMessage("<|channel|> <|message|>\n")]),
	);

	assert.equal(end.outcome, "FAILED");
	assert.equal(end.reason, "ERROR");
	assert.deepEqual(end.entries, []);
});

void test("a read in flight names the stage of what it reads, in the developer's words", async () => {
	const toolReported = Promise.withResolvers<undefined>();
	const worker = fakeWorker(
		contextOf([], "is my pull request ready?"),
		async (request) => {
			await toolReported.promise;
			return { ok: true, text: request.path };
		},
		(snapshot) => {
			if (snapshot.activity === "tool") {
				toolReported.resolve(undefined);
			}
		},
	);

	const end = await runTurn(
		worker,
		fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("read", { path: "inputs/context/merge_readiness/42.json" })],
					{ stopReason: "toolUse" },
				),
			() => faux.fauxAssistantMessage("It is ready."),
		]),
	);

	assert.equal(end.outcome, "DONE");
	const tool = worker.partials.find((partial) => partial.activity === "tool");
	assert.equal(tool?.stage, "Reading your pull requests and issues…");
});

void test("a fast read's stage reaches the worker though Hephaestus thinks again within the same window", async () => {
	// The read returns at once and the model's next answer streams slowly: without holding the tool's stage, the
	// window's snapshot would carry only "Thinking…".
	const worker = fakeWorker(contextOf([], "what did the review say?"));

	const end = await runTurn(
		worker,
		fauxModels(
			[
				() =>
					faux.fauxAssistantMessage(
						[
							faux.fauxToolCall("read", {
								path: "inputs/context/results_history.json",
							}),
						],
						{ stopReason: "toolUse" },
					),
				() => faux.fauxAssistantMessage("It found two points to work on. ".repeat(20)),
			],
			400,
		),
		250,
	);

	assert.equal(end.outcome, "DONE");
	assert.ok(
		worker.partials.some((partial) => partial.stage === "Reading your practice feedback…"),
		JSON.stringify(worker.partials.map((partial) => partial.stage)),
	);
});

function fetching(path: string): string {
	return turnStage("read", { path });
}

const DOCUMENTS = [
	"user",
	"workspace",
	"recent_authored_work",
	"activity",
	"merge_readiness",
	"review_attempts",
	"results_history",
	"delivered_feedback",
	"prepared_conversation_feedback",
	"practice_profile",
	"practice_catalog",
	"slack_conversations",
	"outline_docs",
	"weekly_digest",
];

void test("every context document and tool has one stage label, and anything else is thinking", () => {
	assert.equal(
		fetching("inputs/context/recent_authored_work.json"),
		"Reading your pull requests and issues…",
	);
	assert.equal(fetching("inputs/context/issue/77.json"), "Reading your pull requests and issues…");
	assert.equal(fetching("inputs/context/activity.json"), "Reading your pull requests and issues…");
	assert.equal(
		fetching("inputs/context/results_history/0b6c1f0e-8d3a-4c51-9a4e-2f7d5c9e1a33.json"),
		"Reading your practice feedback…",
	);
	assert.equal(
		fetching("inputs/context/review_attempts/issue/21.json"),
		"Reading your practice feedback…",
	);
	assert.equal(fetching("inputs/context/practice_profile.json"), "Reading your practice feedback…");
	assert.equal(fetching("inputs/context/weekly_digest.json"), "Reading your practice feedback…");
	assert.equal(fetching("inputs/context/slack_conversations.json"), "Reading your team's Slack…");
	assert.equal(fetching("inputs/context/outline_docs.json"), "Reading your team's docs…");
	assert.equal(
		turnStage("search_conversation", { query: "x" }),
		"Looking back through this conversation…",
	);
	assert.equal(turnStage("link_result", {}), THINKING_STAGE);
	assert.equal(turnStage("link_problem", {}), THINKING_STAGE);
	assert.equal(turnStage("show_feedback", {}), THINKING_STAGE);
	assert.equal(fetching("../etc/passwd"), THINKING_STAGE);
	assert.equal(fetching("INDEX.md"), THINKING_STAGE);
	assert.equal(
		fetching("/workspace/inputs/context/activity.json"),
		"Reading your pull requests and issues…",
	);
	assert.equal(fetching("repos/github.com/acme/api/README.md"), "Reading the repository…");
	assert.equal(
		turnStage("bash", { command: "jq '.pullRequests[0]' inputs/context/merge_readiness.json" }),
		"Reading your pull requests and issues…",
	);
	assert.equal(
		turnStage("bash", { command: "rg -n Money repos/github.com/acme/api" }),
		"Reading the repository…",
	);
	assert.equal(
		turnStage("bash", { command: "cd repos && git log -n 5" }),
		"Reading the repository…",
	);
	assert.equal(turnStage("bash", { command: "ls" }), THINKING_STAGE);
	assert.equal(
		turnStage("load_context", { path: "repos/github.com/acme/api" }),
		"Loading the repository…",
	);
	for (const document of DOCUMENTS) {
		assert.notEqual(fetching(`inputs/context/${document}.json`), THINKING_STAGE, document);
	}
});

void test("a stop while a tool runs ends the turn stopped, and Pi answers every open tool call on the next request", async () => {
	const toolStarted = Promise.withResolvers<undefined>();
	const worker = fakeWorker(contextOf([], "question"), async (_request, signal) => {
		toolStarted.resolve(undefined);
		const aborted = Promise.withResolvers<SandboxReadAnswer>();
		signal.addEventListener("abort", () => aborted.reject(new Error("stopped")));
		return aborted.promise;
	});
	const host = hostFor(
		worker,
		fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("read", { path: "inputs/context/user.json" })],
					{
						stopReason: "toolUse",
					},
				),
			() => faux.fauxAssistantMessage("never"),
		]),
	);
	const turnId = crypto.randomUUID();
	void host.start(turnId, 1, "turn-token");
	await toolStarted.promise;
	host.stop(turnId, 1, "developer");
	const end = await worker.waitForEnd();
	await host.idle();
	assert.equal(end.outcome, "STOPPED");
	const calls = messages(worker.appended, "assistant").flatMap((entry) =>
		entry.message.role === "assistant"
			? entry.message.content.filter((block) => block.type === "toolCall")
			: [],
	);
	assert.notEqual(calls.length, 0);

	// The next turn reads the stopped one cut after its tool calls, the worst a stop can leave. A provider refuses a
	// tool call without its result; Pi's transformMessages adds one to the request.
	const requests: unknown[] = [];
	const proxy = await fakeProxy(async (_request, body) => {
		requests.push(JSON.parse(body));
		return "after the stop";
	});
	try {
		const ledger = worker.ledger();
		const calling = ledger.findIndex(
			(entry) => isRecord(entry) && isRecord(entry.message) && entry.message.role === "assistant",
		);
		const open = ledger.slice(0, calling + 1);
		const following = fakeWorker(contextOf(open, "go on"));
		const next = await runTurn(following, proxyModels(proxy.url, AGENT_DIR));
		assert.equal(next.outcome, "DONE");
	} finally {
		proxy.close();
	}
	const [request] = requests;
	const sent = isRecord(request) && Array.isArray(request.messages) ? request.messages : [];
	const answered = new Set(
		sent.flatMap((message: unknown) =>
			isRecord(message) && message.role === "tool" && typeof message.tool_call_id === "string"
				? [message.tool_call_id]
				: [],
		),
	);
	for (const call of calls) {
		assert.ok(answered.has(call.id), `the request answers tool call ${call.id}`);
	}
});

void test("a stop before the session exists ends the turn stopped without a model call", async () => {
	let calls = 0;
	const worker = fakeWorker(contextOf([], "question"));
	const contextAsked = Promise.withResolvers<undefined>();
	const stopSent = Promise.withResolvers<undefined>();
	const api = worker.api();
	const host = createConversationLoop({
		cwd: CWD,
		agentDir: AGENT_DIR,
		turnApi: () => ({
			...api,
			context: async () => {
				contextAsked.resolve(undefined);
				await stopSent.promise;
				return api.context();
			},
		}),
		models: fauxModels([
			() => {
				calls += 1;
				return faux.fauxAssistantMessage("never");
			},
		]),
	});
	const turnId = crypto.randomUUID();
	void host.start(turnId, 1, "turn-token");
	await contextAsked.promise;
	host.stop(turnId, 1, "developer");
	stopSent.resolve(undefined);
	const end = await worker.waitForEnd();
	await host.idle();
	assert.equal(end.outcome, "STOPPED");
	assert.equal(calls, 0);
});

function completionChunk(delta: Record<string, unknown>, finish: string | null): string {
	const chunk = { id: "c", object: "chat.completion.chunk", created: 1, model: "m" };
	return `data: ${JSON.stringify({ ...chunk, choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
}

/** An OpenAI-compatible endpoint standing in for the worker's LLM proxy. */
async function fakeProxy(answer: (request: IncomingMessage, body: string) => Promise<string>) {
	const server = createServer((request, response) => {
		let body = "";
		request.on("data", (chunk: Buffer) => {
			body += chunk.toString("utf8");
		});
		request.on("end", () => {
			void (async () => {
				const text = await answer(request, body);
				response.writeHead(200, { "content-type": "text/event-stream" });
				response.write(completionChunk({ role: "assistant", content: text }, null));
				response.write(completionChunk({}, "stop"));
				response.end("data: [DONE]\n\n");
			})();
		});
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("no port");
	}
	return { url: `http://127.0.0.1:${address.port}`, close: () => server.close() };
}

void test("two conversations run at once, each with its own credential on its own model calls", async () => {
	const inFlight = { now: 0, most: 0 };
	// Each model call answers once both are in flight, which only turns running at once can reach.
	const bothAsked = Promise.withResolvers<undefined>();
	const seen: string[] = [];
	const proxy = await fakeProxy(async (request, body) => {
		inFlight.now += 1;
		inFlight.most = Math.max(inFlight.most, inFlight.now);
		if (inFlight.now === 2) {
			bothAsked.resolve(undefined);
		}
		await bothAsked.promise;
		inFlight.now -= 1;
		const conversation = body.includes("main question") ? "main" : "thread";
		seen.push(`${conversation}:${request.headers.authorization ?? ""}`);
		return `answer for ${conversation}`;
	});
	try {
		const main = fakeWorker(contextOf([], "main question"));
		const thread = fakeWorker(contextOf([], "thread question"));
		const host = createConversationLoop({
			cwd: CWD,
			agentDir: AGENT_DIR,
			turnApi: (turnId) => (turnId === "main" ? main : thread).api(),
			models: proxyModels(proxy.url, AGENT_DIR),
		});
		void host.start("main", 1, "token-main");
		void host.start("thread", 1, "token-thread");
		assert.equal(host.running, 2);
		const ends = await Promise.all([main.waitForEnd(), thread.waitForEnd()]);
		await host.idle();

		assert.deepEqual(
			ends.map((end) => end.outcome),
			["DONE", "DONE"],
		);
		assert.equal(inFlight.most, 2, "both turns waited on their model at the same time");
		assert.deepEqual(seen.toSorted(), ["main:Bearer token-main", "thread:Bearer token-thread"]);
		assert.equal(textOf(messages(main.appended, "assistant").at(-1)?.message), "answer for main");
		assert.equal(
			textOf(messages(thread.appended, "assistant").at(-1)?.message),
			"answer for thread",
		);
	} finally {
		proxy.close();
	}
});

void test("a summary asks for thinking off through the standard control only, with no provider-specific field", async () => {
	const bodies: { summary: Record<string, unknown>[]; answer: Record<string, unknown>[] } = {
		summary: [],
		answer: [],
	};
	const proxy = await fakeProxy(async (_request, body) => {
		const parsed: unknown = JSON.parse(body);
		const request = isRecord(parsed) ? parsed : {};
		const summary = body.includes("<conversation>") || body.includes("# Conversation");
		(summary ? bodies.summary : bodies.answer).push(request);
		return summary ? "## Goal\nthe summary" : "the answer";
	});
	try {
		const context = contextOf(pastTheTrigger(), "the question");
		context.model.reasoningEffort = "HIGH";
		const worker = fakeWorker(context);
		const host = createConversationLoop({
			cwd: CWD,
			agentDir: AGENT_DIR,
			turnApi: () => worker.api(),
			models: proxyModels(proxy.url, AGENT_DIR),
		});
		void host.start(crypto.randomUUID(), 1, "turn-token");
		const end = await worker.waitForEnd();
		await host.idle();

		assert.equal(end.outcome, "DONE");
		assert.ok(bodies.summary.length > 0, "the conversation was summarised");
		for (const summary of bodies.summary) {
			// Thinking off: no effort at all, and no field a single provider's template reads.
			for (const field of [
				"reasoning_effort",
				"enable_thinking",
				"chat_template_kwargs",
				"thinking",
			]) {
				assert.equal(field in summary, false, field);
			}
		}
		assert.equal(bodies.answer.length, 1);
		const answer = bodies.answer.at(0);
		assert.ok(answer);
		assert.equal(answer.reasoning_effort, "high", "the answer keeps the configured effort");
		assert.equal("chat_template_kwargs" in answer, false);
	} finally {
		proxy.close();
	}
});

/** A stand-in for the worker's LLM proxy that answers every model call with `answer`. */
async function proxyAnswering(answer: (response: ServerResponse) => void) {
	const server = createServer((request, response) => {
		request.resume();
		request.on("end", () => answer(response));
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("no port");
	}
	return { url: `http://127.0.0.1:${address.port}`, close: () => server.close() };
}

async function turnAgainst(proxyUrl: string, protocol = "openai-completions"): Promise<TurnEnd> {
	const context = contextOf([], "Is the release ready?");
	context.model.apiProtocol = protocol;
	const worker = fakeWorker(context);
	const host = createConversationLoop({
		cwd: CWD,
		agentDir: AGENT_DIR,
		turnApi: () => worker.api(),
		models: proxyModels(proxyUrl, AGENT_DIR),
	});
	void host.start(crypto.randomUUID(), 1, "turn-token");
	const end = await worker.waitForEnd();
	await host.idle();
	return end;
}

const STALL_PAYLOAD = {
	error: {
		message: "upstream_stalled: the model provider sent nothing for 120 seconds",
		type: "upstream_stalled",
		code: "upstream_stalled",
	},
};
const STALL_ERROR = JSON.stringify(STALL_PAYLOAD);

void test("a turn fails as stalled at once when the proxy ends its stream with a stall error", async () => {
	let calls = 0;
	const proxy = await proxyAnswering((response) => {
		calls += 1;
		response.writeHead(200, { "content-type": "text/event-stream" });
		response.write(completionChunk({ role: "assistant", content: "Let me" }, null));
		response.end(`data: ${STALL_ERROR}\n\n`);
	});
	try {
		const end = await turnAgainst(proxy.url);

		assert.equal(end.outcome, "FAILED");
		assert.equal(end.reason, "STALLED");
		assert.deepEqual(end.entries, []);
		assert.equal(calls, 1, "a stall is not retried");
		assert.equal(
			end.detail,
			"upstream_stalled",
			"the proxy's code, not its words, reaches the worker log",
		);
	} finally {
		proxy.close();
	}
});

void test("a native turn records a named proxy stall and makes no repair call", async () => {
	let calls = 0;
	const proxy = await proxyAnswering((response) => {
		calls += 1;
		response.writeHead(200, { "content-type": "text/event-stream" });
		response.write(
			`event: message_start\ndata: ${JSON.stringify({
				type: "message_start",
				message: {
					id: "m",
					role: "assistant",
					model: "model",
					content: [],
					stop_reason: null,
					usage: { input_tokens: 13, output_tokens: 0 },
				},
			})}\n\n`,
		);
		response.end(`event: error\ndata: ${JSON.stringify({ type: "error", ...STALL_PAYLOAD })}\n\n`);
	});
	try {
		const end = await turnAgainst(proxy.url, "anthropic-messages");
		assert.equal(end.outcome, "FAILED");
		assert.equal(end.reason, "STALLED");
		assert.equal(end.detail, "upstream_stalled");
		assert.equal(calls, 1);
		assert.deepEqual(end.entries, []);
	} finally {
		proxy.close();
	}
});

void test("a turn fails as stalled when the proxy answers a call with a stall or a paused provider", async () => {
	for (const code of ["upstream_stalled", "upstream_paused"]) {
		const proxy = await proxyAnswering((response) => {
			response.writeHead(code === "upstream_stalled" ? 504 : 503, {
				"content-type": "application/json",
			});
			response.end(
				JSON.stringify({ error: { message: `${code}: provider unavailable`, type: code, code } }),
			);
		});
		try {
			const end = await turnAgainst(proxy.url);

			assert.equal(end.outcome, "FAILED", code);
			assert.equal(end.reason, "STALLED", code);
		} finally {
			proxy.close();
		}
	}
});

void test("the cache breakpoint moves from the situation to the end of the stored conversation", () => {
	const payload = {
		messages: [
			{ role: "user", content: [{ type: "text", text: "question" }] },
			{
				role: "user",
				content: [{ type: "text", text: SITUATION, cache_control: { type: "ephemeral" } }],
			},
		],
	};
	assert.equal(breakpointBeforeSituation(payload), payload);
	assert.deepEqual(payload.messages[0]?.content[0], {
		type: "text",
		text: "question",
		cache_control: { type: "ephemeral" },
	});
	assert.equal("cache_control" in (payload.messages[1]?.content[0] ?? {}), false);
	assert.equal(
		breakpointBeforeSituation({ messages: [{ role: "user", content: "no situation" }] }),
		undefined,
	);
});

void test("a stop at the deadline ends the turn failed and sends none of its entries", async () => {
	const { host, turnId, worker } = await turnInATool();

	host.stop(turnId, 1, "deadline");
	const end = await worker.waitForEnd();
	await host.idle();

	assert.deepEqual(end, {
		outcome: "FAILED",
		entries: [],
		reason: "DEADLINE",
		detail: "deadline",
	});
});

void test("a stop for a lost lease ends the turn without a word to the worker", async () => {
	const { host, turnId, ends } = await turnInATool();

	host.stop(turnId, 2, "deadline");
	assert.equal(host.running, 1, "a stop for another attempt does not stop this one");
	host.stop(turnId, 1, "lease");
	await host.idle();

	assert.equal(host.running, 0);
	assert.equal(ends(), 0);
});

void test("a later attempt of a running turn stops the earlier one and then runs with its own credential", async () => {
	const toolStarted = Promise.withResolvers<undefined>();
	const first = fakeWorker(contextOf([], "question"), async (_request, signal) => {
		toolStarted.resolve(undefined);
		const aborted = Promise.withResolvers<SandboxReadAnswer>();
		signal.addEventListener("abort", () => aborted.reject(new Error("stopped")));
		return aborted.promise;
	});
	const second = fakeWorker(contextOf([], "question"));
	const credentials: string[] = [];
	let firstEnds = 0;
	const host = createConversationLoop({
		cwd: CWD,
		agentDir: AGENT_DIR,
		turnApi: (_turnId, token) => {
			credentials.push(token);
			const api = (token === "attempt-1" ? first : second).api();
			return token === "attempt-1"
				? {
						...api,
						end: async (end) => {
							firstEnds += 1;
							await api.end(end);
						},
					}
				: api;
		},
		models: fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("read", { path: "inputs/context/user.json" })],
					{ stopReason: "toolUse" },
				),
			() => faux.fauxAssistantMessage("the retried answer"),
		]),
	});
	const turnId = crypto.randomUUID();
	void host.start(turnId, 1, "attempt-1");
	await toolStarted.promise;

	void host.start(turnId, 2, "attempt-2");
	void host.start(turnId, 1, "attempt-1");
	const end = await second.waitForEnd();
	await host.idle();

	assert.deepEqual(credentials, ["attempt-1", "attempt-2"]);
	assert.equal(firstEnds, 0, "the lost attempt ends nothing");
	assert.equal(end.outcome, "DONE");
	assert.equal(
		textOf(messages(second.appended, "assistant").at(-1)?.message),
		"the retried answer",
	);
});

void test("an end the worker refuses for its size is sent again with the context folder results as notes", async () => {
	const worker = fakeWorker(contextOf([], "question"));
	const refused: TurnEnd[] = [];
	const host = createConversationLoop({
		cwd: CWD,
		agentDir: AGENT_DIR,
		turnApi: () => {
			const api = worker.api();
			return {
				...api,
				end: async (end) => {
					if (refused.length === 0) {
						refused.push(structuredClone(end));
						throw tooLarge("end of turn is larger than the worker accepts");
					}
					await api.end(end);
				},
			};
		},
		models: fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("read", { path: "inputs/context/user.json" })],
					{ stopReason: "toolUse" },
				),
			() => faux.fauxAssistantMessage("read it"),
		]),
	});
	void host.start(crypto.randomUUID(), 1, "turn-token");
	const end = await worker.waitForEnd();
	await host.idle();

	const resultOf = (entries: unknown[]) =>
		textOf(messages(entries.filter(isSessionEntry), "toolResult")[0]?.message);
	assert.match(resultOf(refused[0]?.entries ?? []), /inputs\/context\/user\.json/u);
	assert.doesNotMatch(resultOf(refused[0]?.entries ?? []), /read at/u);
	assert.equal(end.outcome, "DONE");
	assert.equal(end.entries.length, refused[0]?.entries.length);
	assert.match(resultOf(end.entries), /^\[read inputs\/context\/user\.json, read at /u);
	assert.equal(textOf(messages(worker.appended, "assistant").at(-1)?.message), "read it");

	// The next turn finds the note already stored and edits nothing.
	const next = fakeWorker(contextOf(worker.ledger(), "again"));
	await runTurn(next, fauxModels([() => faux.fauxAssistantMessage("again")]));
	assert.ok(next.appended.every((entry) => entry.type !== "context_edit"));
});

async function turnInATool(worker?: FakeWorker) {
	const toolStarted = Promise.withResolvers<undefined>();
	const blocked =
		worker ??
		fakeWorker(contextOf([], "question"), async (_request, signal) => {
			toolStarted.resolve(undefined);
			const aborted = Promise.withResolvers<SandboxReadAnswer>();
			signal.addEventListener("abort", () => aborted.reject(new Error("stopped")));
			return aborted.promise;
		});
	let ends = 0;
	const host = createConversationLoop({
		cwd: CWD,
		agentDir: AGENT_DIR,
		turnApi: () => {
			const api = blocked.api();
			return {
				...api,
				end: async (end) => {
					ends += 1;
					await api.end(end);
				},
			};
		},
		models: fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("read", { path: "inputs/context/user.json" })],
					{ stopReason: "toolUse" },
				),
		]),
	});
	const turnId = crypto.randomUUID();
	void host.start(turnId, 1, "turn-token");
	await toolStarted.promise;
	return { host, turnId, worker: blocked, ends: () => ends };
}
