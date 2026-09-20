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
		'export default () => ({hints: [], metrics: {found: 1}, directions: ["inspect sample"]});\n',
	);

	const { status, stderr } = spawnSync(
		process.execPath,
		[path.join(import.meta.dirname, "runner.ts"), "--repo", root, "--output", output],
		{ encoding: "utf8", stdio: ["ignore", "ignore", "pipe"] },
	);

	assert.equal(status, 0, stderr);
	assert.deepEqual(JSON.parse(await readFile(path.join(output, "sample.json"), "utf8")), {
		practice: "sample",
		status: "ok",
		hints: [],
		metrics: { found: 1 },
		directions: ["inspect sample"],
	});
	assert.match(await readFile(path.join(output, "summary.md"), "utf8"), /## sample/u);
	assert.ok(await readFile(path.join(output, ".complete"), "utf8"));
});
