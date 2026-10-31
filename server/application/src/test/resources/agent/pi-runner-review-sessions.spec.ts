import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { executionReportSchema } from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import { z } from "../../../../../../docker/agents/practice-scripts/lib/core/practice-script.ts";
import {
	readJsonFile,
	runRunner,
	SESSION_CANDIDATE,
	stagePractices,
} from "./program-host-runner.ts";

const manifestSchema = z.looseObject({
	files: z.array(z.looseObject({ kind: z.string(), file: z.object({ path: z.string() }) })),
});
const debugSchema = z.looseObject({
	practiceSources: z.record(
		z.string(),
		z.array(z.looseObject({ path: z.string(), supplied: z.boolean(), read: z.string() })),
	),
});

const MET = {
	...SESSION_CANDIDATE,
	problems: undefined,
	outcome: "MET",
	evidence: {},
	citations: [{ path: "evidence/metadata.json", startLine: 1, quote: "Add login" }],
};

/** One built-in practice; with `unread`, a required source that the session never reads. */
async function review(t: test.TestContext, unread: boolean) {
	const cwd = mkdtempSync(path.join(tmpdir(), "pi-review-sessions-"));
	t.after(() => rmSync(cwd, { recursive: true, force: true }));
	stagePractices(cwd, [{ slug: "test-practice" }]);
	if (unread) {
		const manifestPath = path.join(cwd, "evidence/manifest.json");
		const manifest = manifestSchema.parse(JSON.parse(readFileSync(manifestPath, "utf8")));
		writeFileSync(path.join(cwd, "evidence/unseen.json"), '{"body":"Independent evidence"}');
		manifest.files.push({ kind: "scm.pull-request.core", file: { path: "evidence/unseen.json" } });
		writeFileSync(manifestPath, JSON.stringify(manifest));
	}
	const exit = await runRunner(cwd, {}, { PI_HOST_SESSION_CANDIDATE: JSON.stringify(MET) });
	const report = executionReportSchema.parse(readJsonFile(cwd, "out/practice-scripts.json"));
	const [entry] = report.practices;
	assert.ok(entry, exit.stderr);
	return { cwd, exit, entry };
}

void test("a MET result the evidence check refused reports the refusals, not a format failure", async (t) => {
	const { cwd, entry } = await review(t, true);
	assert.equal(entry.status, "failed");
	// The stored stop reason stays as before; the typed counts tell the cause apart.
	assert.equal(entry.stopReason, "off-format");
	assert.ok(entry.reviewSessions, "the runner reports what the review sessions did");
	assert.ok(entry.reviewSessions.evidenceRefusals >= 1);
	assert.equal(entry.reviewSessions.formatRefusals, 0);
	assert.ok(entry.reviewSessions.modelCalls >= 1);

	const debug = debugSchema.parse(readJsonFile(cwd, "out/runner-debug.json"));
	const unseen = debug.practiceSources["test-practice"]?.find(
		(source) => source.path === "evidence/unseen.json",
	);
	assert.deepEqual(unseen && { supplied: unseen.supplied, read: unseen.read }, {
		supplied: false,
		read: "none",
	});
	assert.doesNotMatch(JSON.stringify(debug.practiceSources), /Independent evidence/u);
});

void test("an admitted result reports its review calls and no refusals", async (t) => {
	const { entry } = await review(t, false);
	assert.equal(entry.status, "ok");
	assert.deepEqual(entry.reviewSessions, { modelCalls: 1, evidenceRefusals: 0, formatRefusals: 0 });
});

void test("a practice cut off by a crash is reported not reached, without session counts the server refuses", async (t) => {
	const cwd = mkdtempSync(path.join(tmpdir(), "pi-review-sessions-crash-"));
	t.after(() => rmSync(cwd, { recursive: true, force: true }));
	stagePractices(cwd, [{ slug: "test-practice" }]);
	const exit = await runRunner(cwd, { "test-practice": "crash" });
	assert.equal(exit.status, 2, exit.stderr);
	assert.match(exit.stderr, /review\.fatal error=Error at=program-host-runner\.ts:\d+/u);
	const report = executionReportSchema.parse(readJsonFile(cwd, "out/practice-scripts.json"));
	const [entry] = report.practices;
	assert.equal(entry?.status, "not-reached");
	// A program that did not start opened no session (PiResultParser drops such an entry).
	assert.equal(entry.reviewSessions, undefined);
});
