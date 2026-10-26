import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";
import { z } from "zod";
import { requested, REVIEWED } from "./run-bundled.ts";

const cli = path.join(import.meta.dirname, "validate.ts");
async function workspace(t: TestContext, source: string) {
	const root = await mkdtemp(path.join(tmpdir(), "practice-validate-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(root, "repo"));
	await mkdir(path.join(root, "out"));
	await writeFile(path.join(root, "fixture.ts"), source);
	const inputs = { ...requested(), practice: { ...requested().practice, slug: "fixture" } };
	await writeFile(path.join(root, "inputs.json"), JSON.stringify(inputs));
	await writeFile(path.join(root, "reviewed.json"), JSON.stringify(REVIEWED));
	const args = [
		cli,
		"--script",
		path.join(root, "fixture.ts"),
		"--repo",
		path.join(root, "repo"),
		"--inputs",
		path.join(root, "inputs.json"),
		"--out",
		path.join(root, "out"),
	];
	return { root, args, inputs };
}
function run(args: string[]) {
	return spawnSync(process.execPath, args, { encoding: "utf8" });
}
async function report(root: string) {
	const value: unknown = JSON.parse(await readFile(path.join(root, "out/fixture.json"), "utf8"));
	return z
		.object({
			status: z.string(),
			stopReason: z.string().nullable(),
			candidate: z.unknown().optional(),
			reviews: z.array(z.unknown()),
			error: z.string().optional(),
		})
		.parse(value);
}

void test("the author tool returns an authored candidate without model access", async (t) => {
	const { root, args } = await workspace(
		t,
		`export default {meta:{},run:async()=>(${JSON.stringify({ ...REVIEWED, coverage: undefined })})};`,
	);
	const exit = run(args);
	assert.equal(exit.status, 0, exit.stderr);
	const result = await report(root);
	assert.equal(result.status, "ok");
	assert.deepEqual(result.reviews, []);
});
void test("review without a supplied result remains unavailable rather than making up an outcome", async (t) => {
	const { root, args } = await workspace(
		t,
		"export default {meta:{},run:async ctx=>ctx.review()};",
	);
	const exit = run(args);
	assert.equal(exit.status, 1);
	const result = await report(root);
	assert.equal(result.stopReason, "unavailable");
});
void test("the author tool passes the supplied review result through the host", async (t) => {
	const { root, args } = await workspace(
		t,
		'export default {meta:{},run:async ctx=>ctx.review({directions:["Read the complete standard."]})};',
	);
	const exit = run([...args, "--review", path.join(root, "reviewed.json")]);
	assert.equal(exit.status, 0, exit.stderr);
	const result = await report(root);
	assert.deepEqual(result.candidate, REVIEWED);
	assert.equal(result.reviews.length, 1);
});
void test("review origin and previous proof come from the single frozen input file", async (t) => {
	const { root, args, inputs } = await workspace(
		t,
		"export default {meta:{},run:async ctx=>ctx.review({directions:[ctx.review.origin]})};",
	);
	await writeFile(
		path.join(root, "inputs.json"),
		JSON.stringify({ ...inputs, review: { ...inputs.review, origin: "BACKFILL" } }),
	);
	const exit = run([...args, "--review", path.join(root, "reviewed.json")]);
	assert.equal(exit.status, 0, exit.stderr);
	assert.match(exit.stdout, /BACKFILL/u);
});
void test("v2 and mismatched assignment inputs are refused before script execution", async (t) => {
	const { root, args, inputs } = await workspace(
		t,
		"export default {meta:{},run:async ctx=>ctx.review()};",
	);
	for (const invalid of [
		{ ...inputs, contractVersion: 2 },
		{ ...inputs, practice: { ...inputs.practice, slug: "another" } },
	]) {
		await writeFile(path.join(root, "inputs.json"), JSON.stringify(invalid));
		const exit = run(args);
		assert.equal(exit.status, 1);
		const result = await report(root);
		assert.equal(result.stopReason, "unavailable");
		assert.deepEqual(result.reviews, []);
	}
});
void test("a required unbound model withholds and a missing candidate fails", async (t) => {
	for (const [source, status] of [
		['export default {meta:{models:{chat:"required"}},run:async ctx=>ctx.review()};', "withheld"],
		["export default {meta:{},run:async()=>undefined};", "failed"],
	] as const) {
		const { root, args } = await workspace(t, source);
		const exit = run(args);
		assert.equal(exit.status, 1);
		const result = await report(root);
		assert.equal(result.status, status);
	}
});
void test("missing inputs and unknown options are command errors", () => {
	for (const args of [[cli], [cli, "--context", "context"]]) {
		const exit = run(args);
		assert.equal(exit.status, 2);
		assert.match(exit.stderr, /required|Unknown option/u);
	}
});
