// A review's measurement reads the server's context strictly, and gives each program the gateway's routes and claim.

import assert from "node:assert/strict";
import test from "node:test";

import {
	measureEnvironment,
	parseMeasureContext,
} from "../../../main/resources/agent/review-measure.ts";

const RUN = "0b1d4b71-5f1e-4a52-9a43-7e0c4b0d8a11";

const context = {
	command: ["sh", "-c", "node /workspace/.run-pi.js"],
	env: { AGENT_BUDGET_MS: "60000", GIT_CONFIG_NOSYSTEM: "1" },
	workDeadline: 1000,
	uploadDeadline: 2000,
	programs: false,
};

void test("a measurement context that names a runner, its environment and its deadlines is read whole", () => {
	assert.deepEqual(parseMeasureContext(context), context);
	for (const broken of [
		null,
		[],
		{ ...context, command: [] },
		{ ...context, command: ["sh", ""] },
		{ ...context, env: { AGENT_BUDGET_MS: 60_000 } },
		{ ...context, uploadDeadline: context.workDeadline },
		{ ...context, workDeadline: "soon" },
		{ ...context, programs: "yes" },
	]) {
		assert.throws(() => parseMeasureContext(broken), /measurement context/u);
	}
});

void test("each program of the measurement reaches the gateway with the sandbox token and the run's claim", () => {
	const env = measureEnvironment(
		{
			assignment: { runId: RUN, epoch: 3, kind: "REVIEW", leasedUntil: 0, deadline: null },
			gateway: {
				url: "http://10.43.0.12:8081",
				headers: {
					authorization: "Bearer sandbox-token",
					"x-agent-run": RUN,
					"x-agent-epoch": "3",
				},
			},
			signal: new AbortController().signal,
		},
		parseMeasureContext(context),
	);

	assert.equal(env.AGENT_BUDGET_MS, "60000");
	assert.equal(env.LLM_PROXY_URL, "http://10.43.0.12:8081/internal/llm");
	assert.equal(env.LLM_PROXY_TOKEN, "sandbox-token");
	assert.deepEqual(JSON.parse(env.LLM_PROXY_CLAIM ?? ""), {
		"x-agent-run": RUN,
		"x-agent-epoch": "3",
	});
	assert.equal(
		env.SANDBOX_RUNTIME_URL,
		`http://10.43.0.12:8081/internal/agent/runs/${RUN}/runtime`,
	);
	assert.equal(env.SANDBOX_WORK_DEADLINE_MS, "1000");
	assert.equal(env.SANDBOX_UPLOAD_DEADLINE_MS, "2000");
});
