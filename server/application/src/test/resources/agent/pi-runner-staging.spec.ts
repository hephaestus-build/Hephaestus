import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	capturedLines,
	directProgram,
	field,
	runRunner,
	stagePractices,
} from "./program-host-runner.ts";

void test("an unavailable practice stays open while completed work is staged for its retry", async () => {
	const first = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-stage-outage-")));
	const retry = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-stage-retry-")));
	try {
		stagePractices(first, [{ slug: "finished" }, { slug: "unavailable", inputs: false }]);
		const interrupted = await runRunner(first, {});
		// A designed partial end, not a failure: the worker reads the open practice by its index.
		assert.equal(interrupted.status, 78, interrupted.stderr);
		assert.match(
			interrupted.stderr,
			/^\[pi-runner\] practice\.without_result practice=1 program_stop=\S+$/mu,
		);
		const staged = capturedLines(first, "staging").map((entry) => field(entry, "body"));
		assert.equal(staged.length, 1);
		const results = field(staged[0], "results");
		assert.ok(Array.isArray(results));
		assert.equal(field(results[0], "practiceSlug"), "finished");
		assert.deepEqual(capturedLines(first, "admission"), []);

		// The server grants only the membership left open by the previous attempt.
		stagePractices(retry, [{ slug: "unavailable" }]);
		const completed = await runRunner(retry, {});
		assert.equal(completed.status, 0, completed.stderr);
		assert.deepEqual(
			capturedLines(retry, "model-start").map((entry) => field(entry, "slug")),
			["unavailable"],
		);
		assert.equal(capturedLines(retry, "staging").length, 1);
		assert.equal(capturedLines(retry, "admission").length, 1);
	} finally {
		rmSync(first, { recursive: true, force: true });
		rmSync(retry, { recursive: true, force: true });
	}
});

void test("a provider outage stages no withholding and a later attempt measures the practice", async () => {
	const first = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-stage-provider-")));
	const retry = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-stage-provider-retry-")));
	try {
		stagePractices(first, [{ slug: "provider" }]);
		const unavailable = await runRunner(first, { provider: "unreachable" });
		assert.equal(unavailable.status, 76, unavailable.stderr);
		assert.deepEqual(capturedLines(first, "staging"), []);
		assert.deepEqual(capturedLines(first, "admission"), []);
		stagePractices(retry, [{ slug: "provider" }]);
		const completed = await runRunner(retry, {});
		assert.equal(completed.status, 0, completed.stderr);
		assert.equal(capturedLines(retry, "model-start").length, 1);
		const [seal] = capturedLines(retry, "admission");
		assert.deepEqual(field(seal, "body"), {
			contractVersion: 3,
			reviewId: "11111111-1111-4111-8111-111111111111",
			jobId: "22222222-2222-4222-8222-222222222222",
			attempt: 1,
		});
		assert.equal(
			createHash("sha256")
				.update(JSON.stringify(field(seal, "body")))
				.digest("hex"),
			"bcfdeaa2d6beeb2c11618f54111222cc92b2ee55b48eef3c586a84b344c9a6a4",
		);
	} finally {
		rmSync(first, { recursive: true, force: true });
		rmSync(retry, { recursive: true, force: true });
	}
});

for (const concurrent of [false, true]) {
	void test(`gateway response loss requeues without staging${concurrent ? " while another review is active" : ""}`, async () => {
		const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-gateway-requeue-")));
		let calls = 0;
		const server = createServer((request, response) => {
			request.on("end", () => {
				calls += 1;
				response.writeHead(200, { "content-type": "application/json" });
				response.write("{");
				setImmediate(() => {
					response.destroy();
				});
			});
			request.resume();
		});
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		try {
			const address = server.address();
			assert.ok(address !== null && typeof address !== "string");
			stagePractices(cwd, [
				...(concurrent ? [{ slug: "a-waiting" }] : []),
				{
					slug: "b-direct",
					program: directProgram(`
			await step("one call", () => generateText({
				model: ctx.models.chat, prompt: "Say yes.", maxOutputTokens: 1000,
			}));`),
				},
			]);
			writeFileSync(
				path.join(cwd, "practice-script-models.json"),
				JSON.stringify({
					chat: { protocol: "openai-completions", modelId: "chat-model" },
				}),
			);

			const interrupted = await runRunner(cwd, concurrent ? { "a-waiting": "hang" } : {}, {
				LLM_PROXY_URL: `http://127.0.0.1:${address.port}`,
			});

			assert.equal(interrupted.status, 75, interrupted.stderr);
			assert.equal(calls, 3);
			if (concurrent) {
				assert.ok(
					capturedLines(cwd, "model-start").some((entry) => field(entry, "slug") === "a-waiting"),
				);
			}
			assert.deepEqual(capturedLines(cwd, "staging"), []);
			assert.deepEqual(capturedLines(cwd, "admission"), []);
			assert.doesNotMatch(interrupted.stderr, /provider\.retrying/u);
		} finally {
			const closed = once(server, "close");
			server.closeAllConnections();
			server.close();
			await closed;
			rmSync(cwd, { recursive: true, force: true });
		}
	});
}
