import assert from "node:assert/strict";
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { after, test } from "node:test";

import {
	executionReportSchema,
	PRACTICE_SCRIPT_PRACTICE_HEADER,
} from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import {
	chatMessage,
	startStandIn,
} from "../../../../../../docker/agents/practice-scripts/test/proxy-stand-in.ts";
import {
	capturedLines,
	privateModelProgram,
	field,
	readJsonFile,
	runRunner,
	stagePractices,
} from "./program-host-runner.ts";

const JOB_TOKEN = "job-token";
const CALL_TOKENS = 2000;
const ACCOUNT = 5000;

const call = (maxOutputTokens: number) => `
		const answer = await step("one call", () =>
			generateText({ model: ctx.models.chat, prompt: "Say yes.", maxOutputTokens: ${maxOutputTokens} }),
		);
		note += \` \${"value" in answer ? "answered" : answer.unrated} left \${ctx.budget.remaining().tokens}\`;`;
const atStart = `note = \`start \${ctx.budget.remaining().tokens}\`;`;

const standIn = await startStandIn(JOB_TOKEN, async () => ({
	...chatMessage("yes"),
	usage: { prompt_tokens: 1500, completion_tokens: 500, total_tokens: CALL_TOKENS },
}));
const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-direct-budget-")));
after(async () => {
	await standIn.close();
	rmSync(cwd, { recursive: true, force: true });
});

// Direct-model accounting exercises private toolkit helpers.
stagePractices(
	cwd,
	[
		{ slug: "a-spends", program: privateModelProgram(call(100)) },
		{ slug: "b-native" },
		{ slug: "c-refused", program: privateModelProgram(`${atStart}${call(4096)}`) },
		{ slug: "d-small", program: privateModelProgram(`${atStart}${call(100)}`) },
	],
	"work/practice-scripts/toolkit-fixtures",
);
writeFileSync(
	nodePath.join(cwd, "practice-script-models.json"),
	JSON.stringify({ chat: { protocol: "openai-completions", modelId: "chat-model" } }),
);
const exit = await runRunner(
	cwd,
	{},
	{
		LLM_PROXY_URL: standIn.url,
		LLM_PROXY_TOKEN: JOB_TOKEN,
		PI_DIRECT_MODEL_TOKENS: String(ACCOUNT),
		PRACTICE_SCRIPT_PROXY_TOKEN: "practice-script-token",
	},
);

function summaries(): Map<unknown, unknown> {
	const results: unknown = field(readJsonFile(cwd, "out/result.json"), "results");
	assert.ok(Array.isArray(results));
	return new Map(
		results.map((result: unknown): [unknown, unknown] => [
			field(result, "practiceSlug"),
			field(field(result, "candidate"), "summary"),
		]),
	);
}

void test("the run ends with every practice admitted", () => {
	assert.equal(exit.status, 0, exit.stderr);
	assert.deepEqual([...summaries().keys()], ["a-spends", "b-native", "c-refused", "d-small"]);
});

void test("a practice's direct call spends the shared account, and the next practice sees only what remains", () => {
	const left = ACCOUNT - CALL_TOKENS;
	assert.equal(
		summaries().get("a-spends"),
		`No user interface changes; note none answered left ${left}`,
	);
	assert.equal(
		summaries().get("d-small"),
		`No user interface changes; note start ${left} answered left ${left - CALL_TOKENS}`,
	);
});

void test("a native review session's calls are not charged to the direct account", () => {
	assert.equal(capturedLines(cwd, "prompts").length, 1);
	assert.match(
		String(summaries().get("c-refused")),
		new RegExp(`start ${ACCOUNT - CALL_TOKENS} `, "u"),
	);
});

void test("a call above what remains is not rated budget and never reaches the proxy", () => {
	assert.equal(
		summaries().get("c-refused"),
		`No user interface changes; note start ${ACCOUNT - CALL_TOKENS} budget left ${ACCOUNT - CALL_TOKENS}`,
	);
	assert.equal(
		standIn.requests.some(
			(request) => request.headers[PRACTICE_SCRIPT_PRACTICE_HEADER] === "c-refused",
		),
		false,
	);
	const parsed = executionReportSchema.safeParse(readJsonFile(cwd, "out/practice-scripts.json"));
	assert.ok(parsed.success, parsed.error?.message);
	const entry = parsed.data.practices.find((item) => item.practiceSlug === "c-refused");
	assert.deepEqual(entry?.models, [
		{ slot: "chat", need: "optional", bound: true, failures: { budget: 1 } },
	]);
});

void test("every direct call names its practice and carries the one job token", () => {
	assert.deepEqual(
		standIn.requests.map((request) => ({
			route: `${request.slot}/${request.operation}`,
			practice: request.headers[PRACTICE_SCRIPT_PRACTICE_HEADER],
			authorization: request.headers.authorization,
		})),
		["a-spends", "d-small"].map((practice) => ({
			route: "chat/chat/completions",
			practice,
			authorization: `Bearer ${JOB_TOKEN}`,
		})),
	);
});
