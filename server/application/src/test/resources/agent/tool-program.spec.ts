// A practice program that the tools side controls: the host, in the loop, against a hostile process on a real socket.
// Each case names what a compromised tools side tries, and what it must not reach.

import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import type { Server } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, before } from "node:test";

import {
	type Candidate,
	practiceInputsSchema,
} from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import {
	EvidenceReader,
	noReceipts,
} from "../../../../../../docker/agents/practice-scripts/lib/core/evidence-reader.ts";
import { ModelBudget } from "../../../../../../docker/agents/practice-scripts/lib/core/models-host.ts";
import {
	runCustomProgram,
	type ProgramServices,
} from "../../../../../../docker/agents/practice-scripts/lib/core/script-run.ts";
import { requested } from "../../../../../../docker/agents/practice-scripts/run-bundled.ts";
import { ToolClient } from "../../../main/resources/agent/tool-client.ts";
import { toolsProgram } from "../../../main/resources/agent/tool-program.ts";
import { startToolsServer } from "../../../main/resources/agent/tools-server.ts";

const base = mkdtempSync(path.join(tmpdir(), "tool-program-"));
const workspace = path.join(base, "workspace");
const scratch = path.join(workspace, ".scratch");
mkdirSync(scratch, { recursive: true });
const seen = path.join(base, "seen.jsonl");
const socketPath = path.join(base, "tools.sock");
const nonce = "pod-nonce";
const SECRET = "bytes of the session that the tools side must never see";
const REVIEWED: Candidate = {
	outcome: "COULD_NOT_DETERMINE",
	summary: SECRET,
	explanation: "The fake session answers every review with this result.",
	citations: [
		{
			sourceKind: "scm.pull-request.core",
			sourcePath: "context/metadata.json",
			path: "context/metadata.json",
			startLine: 1,
			endLine: 1,
			quote: "{",
		},
	],
	evidence: { openQuestions: [{ openQuestion: "Which?", wouldSettleIt: "A real review." }] },
	coverage: {
		source: "host",
		reads: 0,
		truncatedReads: 0,
		failedReads: 0,
		receipts: [],
		complete: true,
	},
};

/** Says what the case asks for, by the job's slug, once the host accepted its meta. */
const hostile = path.join(base, "hostile.mjs");
writeFileSync(
	hostile,
	`import { appendFileSync } from "node:fs";
const post = (message) => process.send(JSON.stringify(message));
const big = "x".repeat(300_000);
const scenarios = {
	"second-review": () => { post({ kind: "review", id: 1, options: {} }); post({ kind: "review", id: 2, options: {} }); },
	"not-allowed": () => post({ kind: "submit", id: 1, value: { outcome: "MET" } }),
	"big-options": () => post({ kind: "review", id: 1, options: { directions: [big] } }),
	"unknown-option": () => post({ kind: "review", id: 1, options: { tools: ["bash"], path: "/etc/passwd" } }),
	"outside-read": () => post({ kind: "read", id: 1, request: { op: "read", path: "../../../etc/passwd" } }),
	"review": () => post({ kind: "review", id: 1, options: {} }),
	"not-json": () => process.send("{"),
};
process.on("message", (message) => {
	appendFileSync(${JSON.stringify(seen)}, JSON.stringify(message) + "\\n");
	if (message.kind === "job") { globalThis.slug = message.job.slug; post({ kind: "meta", meta: {} }); }
	if (message.kind === "meta-accepted") scenarios[globalThis.slug]();
	if (message.kind === "read-failed") post({ kind: "failed", message: "refused: " + message.reason });
});
`,
);

let server: Server;
let client: ToolClient;

before(async () => {
	server = startToolsServer({
		address: { path: socketPath },
		nonce,
		workspace,
		roots: [workspace],
		scratch,
		putRoots: [scratch],
		scriptEntry: hostile,
		soleUser: false,
	});
	await once(server, "listening");
	client = await ToolClient.open({ path: socketPath }, nonce);
});

after(() => {
	client.close();
	server.close();
});

async function attempt(slug: string) {
	writeFileSync(seen, "");
	const reviews: unknown[] = [];
	const services: ProgramServices = {
		start: ({ timeoutMs }) => toolsProgram(client, { execArgv: [], timeoutMs }),
		reader: new EvidenceReader(
			{
				repo: null,
				headRevision: null,
				treeSourceFile: "",
				contextDir: null,
				contextReference: "",
				change: null,
				sourceKindOf: () => undefined,
			},
			noReceipts(),
		),
		models: {},
		budget: new ModelBudget(1000, 1),
		awaitModel: () => () => undefined,
		review: async (options) => {
			reviews.push(options);
			return REVIEWED;
		},
		signal: new AbortController().signal,
		log: () => undefined,
	};
	const outcome = await runCustomProgram(
		path.join(base, "unused.ts"),
		{
			slug,
			inputs: practiceInputsSchema.parse(requested()),
			files: null,
			now: "2026-01-01T00:00:00Z",
			deadline: Date.now() + 10_000,
		},
		services,
	);
	return { outcome, reviews, received: readFileSync(seen, "utf8") };
}

void test("the socket admits the Pod's group and nobody else", () => {
	assert.equal(statSync(socketPath).mode % 0o1000, 0o660);
});

void test("the program side refuses everything but a program", async () => {
	const home = mkdtempSync(path.join(tmpdir(), "programs-only-"));
	const mutable = path.join(home, "workspace");
	mkdirSync(path.join(mutable, ".scratch"), { recursive: true });
	const address = { path: path.join(home, "programs.sock") };
	const programs = startToolsServer({
		address,
		nonce,
		workspace: mutable,
		roots: [mutable],
		scratch: path.join(mutable, ".scratch"),
		putRoots: [path.join(mutable, ".scratch")],
		scriptEntry: hostile,
		programsOnly: true,
		soleUser: false,
	});
	await once(programs, "listening");
	const side = await ToolClient.open(address, nonce);
	const refused = { name: "ToolProtocolError", code: "unknown" };
	try {
		await assert.rejects(
			side.exec({ command: "id", cwd: mutable, timeoutMs: 1000 }, () => undefined),
			refused,
		);
		await assert.rejects(side.read(path.join(mutable, "x"), 1, 1), refused);
		await assert.rejects(side.reset(), refused);
	} finally {
		side.close();
		programs.close();
	}
});

void test("the tools side, which has no program entry, refuses a program", async () => {
	const home = mkdtempSync(path.join(tmpdir(), "tools-only-"));
	const mutable = path.join(home, "workspace");
	mkdirSync(path.join(mutable, ".scratch"), { recursive: true });
	const address = { path: path.join(home, "tools.sock") };
	const tools = startToolsServer({
		address,
		nonce,
		workspace: mutable,
		roots: [mutable],
		scratch: path.join(mutable, ".scratch"),
		putRoots: [path.join(mutable, ".scratch")],
		soleUser: false,
	});
	await once(tools, "listening");
	const side = await ToolClient.open(address, nonce);
	try {
		const channel = side.script({ execArgv: [], timeoutMs: 1000 }, () => undefined, () => undefined);
		await assert.rejects(channel.exit, { name: "ToolProtocolError", code: "unknown" });
	} finally {
		side.close();
		tools.close();
	}
});

void test("a program gets one session, however many it asks for", async () => {
	const { reviews, outcome } = await attempt("second-review");
	assert.equal(reviews.length, 1);
	assert.equal(outcome.calls.review, 1);
});

void test("nothing of a session reaches the program", async () => {
	const { received, outcome } = await attempt("review");
	assert.equal(received.includes(SECRET), false);
	assert.equal(JSON.stringify(outcome.value).includes(SECRET), true, "the loop kept the result");
});

void test("a request outside the allowlist ends the program and starts nothing", async () => {
	const { reviews, outcome } = await attempt("not-allowed");
	assert.deepEqual(reviews, []);
	assert.equal(outcome.failure?.stopReason, "error");
});

void test("a broken message ends the program and starts nothing", async () => {
	const { reviews, outcome } = await attempt("not-json");
	assert.deepEqual(reviews, []);
	assert.equal(outcome.failure?.stopReason, "error");
});

for (const slug of ["big-options", "unknown-option"]) {
	void test(`a review that asks for more than its options allow opens no session: ${slug}`, async () => {
		const { reviews } = await attempt(slug);
		assert.deepEqual(reviews, []);
	});
}

void test("a read outside the practice's sources is refused by the loop", async () => {
	const { received, outcome } = await attempt("outside-read");
	assert.match(received, /read-failed/u);
	assert.equal(received.includes("root:"), false);
	assert.ok(outcome.failure !== undefined);
});
