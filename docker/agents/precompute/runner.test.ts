import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

void test("runner executes a staged practice and writes its public artifact contract", async () => {
	const root = await mkdtemp(path.join(tmpdir(), "precompute-runner-"));
	const output = path.join(root, "out");
	await mkdir(path.join(output, "practices"), { recursive: true });
	await writeFile(path.join(root, "package.json"), '{"type":"module"}\n');
	await writeFile(
		path.join(output, "practices", "sample.ts"),
		'export default () => ({hints: [{file: "inputs/context/general_comments.json", line: 0, pattern: "conversation ask", context: "Please add the confetti", inDiff: false, flags: {by: "jennifer", authorReplied: false, threadResolved: true}}], metrics: {found: 1}, directions: ["inspect sample"]});\n',
	);

	const { status, stderr } = spawnSync(
		process.execPath,
		[path.join(import.meta.dirname, "runner.ts"), "--repo", root, "--output", output],
		{ encoding: "utf8", stdio: ["ignore", "ignore", "pipe"] },
	);

	assert.equal(status, 0, stderr);
	const written: unknown = JSON.parse(await readFile(path.join(output, "sample.json"), "utf8"));
	assert.ok(typeof written === "object" && written !== null);
	assert.equal(Reflect.get(written, "practice"), "sample");
	assert.equal(Reflect.get(written, "status"), "ok");
	assert.deepEqual(Reflect.get(written, "metrics"), { found: 1 });
	const summary = await readFile(path.join(output, "summary.md"), "utf8");
	assert.match(summary, /## sample/u);
	// A record hint is a row of facts, shown with every flag — a false one is a fact too.
	assert.match(
		summary,
		/\*\*Record facts:\*\*\n- `inputs\/context\/general_comments\.json` — conversation ask: `Please add the confetti` \[by=jennifer, authorReplied=false, threadResolved=true\]/u,
	);
	assert.ok(await readFile(path.join(output, ".complete"), "utf8"));
});
