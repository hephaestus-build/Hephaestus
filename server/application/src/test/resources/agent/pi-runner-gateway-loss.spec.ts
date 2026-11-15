import assert from "node:assert/strict";
import { mkdtempSync, realpathSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { capturedLines, field, runRunner, stagePractices } from "./program-host-runner.ts";

// The server is the review's only gateway. A restart refuses every connection until its replacement listens.
for (const serial of [false, true]) {
	const measurement = serial ? "serial" : "parallel";
	const practices = (second: string) =>
		[{ slug: "a-first" }, { slug: second }].map((practice) =>
			serial ? { ...practice, contextPaths: [] } : practice,
		);

	void test(`a restart while a practice starts is waited out inside the attempt (${measurement})`, async () => {
		const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-gateway-restart-")));
		try {
			stagePractices(cwd, practices("b-restart"));

			const run = await runRunner(
				cwd,
				{},
				// Longer than the retries of an unreachable start, so only the wait for the gateway bridges it.
				{ PI_HOST_START_REFUSED: JSON.stringify({ "b-restart": 9000 }) },
			);

			assert.equal(run.status, 0, run.stderr);
			assert.ok(capturedLines(cwd, "start-refused").length >= 2);
			assert.deepEqual(
				capturedLines(cwd, "model-start")
					.map((entry) => String(field(entry, "slug")))
					.toSorted((left, right) => left.localeCompare(right)),
				["a-first", "b-restart"],
			);
			assert.equal(capturedLines(cwd, "admission").length, 1);
		} finally {
			rmSync(cwd, { recursive: true, force: true });
		}
	});

	void test(`a server that stays gone past the work's end exits retryable, never as a failed review (${measurement})`, async () => {
		const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-gateway-gone-")));
		try {
			stagePractices(cwd, practices("b-gone"));

			// In parallel, the other session still waits for its model when the start fails, so nothing is measured. The
			// swallowed failure used to exit as the provider's outage, or as a failed review once a call was answered.
			const run = await runRunner(cwd, serial ? {} : { "a-first": "hang" }, {
				PI_HOST_START_REFUSED: JSON.stringify({ "b-gone": null }),
				AGENT_BUDGET_MS: "15000",
			});

			assert.equal(run.status, 75, run.stderr);
			assert.deepEqual(capturedLines(cwd, "admission"), []);
		} finally {
			rmSync(cwd, { recursive: true, force: true });
		}
	});
}

void test("an outage past the sandbox's work deadline exits retryable before that deadline, not as a work timeout", async () => {
	const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-gateway-deadline-")));
	try {
		stagePractices(cwd, [{ slug: "a-gone" }]);
		// The sandbox's deadline comes well before the runner's own budget, as when the workspace download took long.
		const workDeadline = Date.now() + 25_000;

		const run = await runRunner(
			cwd,
			{},
			{
				PI_HOST_START_REFUSED: JSON.stringify({ "a-gone": null }),
				SANDBOX_WORK_DEADLINE_MS: String(workDeadline),
			},
		);

		assert.equal(run.status, 75, run.stderr);
		assert.ok(Date.now() < workDeadline, "the runner exited before the sandbox would stop it");
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});
