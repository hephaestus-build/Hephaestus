import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";

const validate = path.join(import.meta.dirname, "validate.ts");
const fixtures = path.join(import.meta.dirname, "fixtures");

const CHANGE = [
	"diff --git a/src/cart.ts b/src/cart.ts",
	"--- a/src/cart.ts",
	"+++ b/src/cart.ts",
	"@@ -1,1 +1,3 @@",
	" export class Cart {",
	"+  // increment the item count by one",
	"+  count += 1;",
].join("\n");

/** A folder with a change, a repository and captured context, as a local run uses them. */
async function workspace(t: TestContext) {
	const root = await mkdtemp(path.join(tmpdir(), "precompute-validate-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(root, "repo"));
	await mkdir(path.join(root, "context"));
	await writeFile(path.join(root, "change.diff"), CHANGE);
	await writeFile(
		path.join(root, "context", "comments.json"),
		'[\n  {"body": "Please split this"}\n]\n',
	);
	return root;
}

function run(args: string[], env: NodeJS.ProcessEnv = process.env) {
	return spawnSync(process.execPath, [validate, ...args], { encoding: "utf8", env });
}

/** The two files that validate.ts prints, by name: each under a `==> <name> <==` line. */
function printed(stdout: string): Map<string, string> {
	const [, json = "", jsonBody = "", section = "", sectionBody = ""] =
		stdout.split(/^==> (?<name>\S+) <==\n/mu);
	return new Map([
		[json, jsonBody.replace(/\n\n$/u, "")],
		[section, sectionBody.replace(/\n$/u, "")],
	]);
}

void test("a definition script runs without models and prints its result and its section", async (t) => {
	const root = await workspace(t);
	const out = path.join(root, "out");
	const { status, stdout, stderr } = run([
		"--script",
		path.join(fixtures, "leads.ts"),
		"--repo",
		path.join(root, "repo"),
		"--diff",
		path.join(root, "change.diff"),
		"--context",
		path.join(root, "context"),
		"--out",
		out,
	]);
	assert.equal(status, 0, stderr);
	// With `--out`, each file is printed under the path that it was written to.
	const files = printed(stdout);
	const jsonPath = path.join(out, "leads.json");
	const sectionPath = path.join(out, "leads.md");
	assert.deepEqual([...files.keys()], [jsonPath, sectionPath]);
	const result: unknown = JSON.parse(files.get(jsonPath) ?? "");
	assert.partialDeepStrictEqual(result, {
		practice: "leads",
		contract: "definition",
		status: "ok",
	});
	const section = files.get(sectionPath) ?? "";
	assert.match(
		section,
		/- restates-code at `src\/cart\.ts` \[L2\]: `\/\/ increment the item count by one`/u,
	);
	// The record is cited under the context folder as it was given.
	assert.match(section, /- record-row at `[^`]*context\/comments\.json:2`/u);
	assert.equal(await readFile(jsonPath, "utf8"), files.get(jsonPath));
	const written = await readFile(sectionPath, "utf8");
	assert.equal(written.trimEnd(), section.trimEnd());
});

void test("a positional script runs as the runner runs it", async (t) => {
	const root = await workspace(t);
	const script = path.join(root, "counts-lines.ts");
	await writeFile(
		script,
		`export default (_repo, diff) => ({
	hints: [],
	metrics: { files: diff.size },
	directions: ["counted"],
});
`,
	);
	const { status, stdout, stderr } = run([
		"--script",
		script,
		"--repo",
		path.join(root, "repo"),
		"--diff",
		path.join(root, "change.diff"),
	]);
	assert.equal(status, 0, stderr);
	const files = printed(stdout);
	assert.partialDeepStrictEqual(JSON.parse(files.get("counts-lines.json") ?? ""), {
		contract: "positional",
		status: "ok",
		metrics: { files: 1 },
	});
	assert.equal(files.get("counts-lines.md"), "- counted\n");
});

void test("a script that requires a model is skipped, and one that fails exits with an error", async (t) => {
	const root = await workspace(t);
	const repo = path.join(root, "repo");
	const skipped = run(["--script", path.join(fixtures, "requires-decision.ts"), "--repo", repo]);
	assert.equal(skipped.status, 0, skipped.stderr);
	assert.partialDeepStrictEqual(
		JSON.parse(printed(skipped.stdout).get("requires-decision.json") ?? ""),
		{
			status: "skipped",
		},
	);
	const failed = run(["--script", path.join(fixtures, "invalid-result.ts"), "--repo", repo]);
	assert.equal(failed.status, 1);
	assert.partialDeepStrictEqual(
		JSON.parse(printed(failed.stdout).get("invalid-result.json") ?? ""),
		{
			status: "error",
		},
	);
});

void test("a script whose process is killed exits with an error that is not a timeout, and no staged folder is left", async (t) => {
	const root = await workspace(t);
	const temporary = path.join(root, "tmp");
	await mkdir(temporary);
	const script = path.join(root, "killed.ts");
	// A kill that the runner did not send, as the kernel sends when memory runs out.
	await writeFile(script, 'export default () => process.kill(process.pid, "SIGKILL");\n');
	const { status, stdout, stderr } = run(["--script", script, "--repo", path.join(root, "repo")], {
		...process.env,
		TMPDIR: temporary,
	});
	assert.equal(status, 1, stderr);
	assert.partialDeepStrictEqual(JSON.parse(printed(stdout).get("killed.json") ?? ""), {
		status: "error",
		error: "the script's process was killed by SIGKILL",
	});
	assert.deepEqual(await readdir(temporary), []);
});

void test("a file whose name is not a practice identifier, or a missing argument, is refused", async (t) => {
	const root = await workspace(t);
	const script = path.join(root, "Not_A_Slug.ts");
	await writeFile(script, "export default () => ({ hints: [], metrics: {}, directions: [] });\n");
	const named = run(["--script", script, "--repo", root]);
	assert.equal(named.status, 2);
	assert.match(named.stderr, /practice's identifier/u);
	const missing = run(["--script", script]);
	assert.equal(missing.status, 2);
	assert.match(
		missing.stderr,
		/^--script and --repo are required\. Usage: node validate\.ts --script <file\.ts> --repo <clone>/u,
	);
});

void test("an option or an input that the command cannot use stops it with one line and exit code 2", async (t) => {
	const root = await workspace(t);
	const repo = path.join(root, "repo");
	const leads = path.join(fixtures, "leads.ts");
	await writeFile(path.join(root, "list.json"), "[]");
	await writeFile(path.join(root, "broken.json"), "{");
	await writeFile(path.join(root, "empty.diff"), "");
	await writeFile(path.join(root, "notes.diff"), "No patch here.\n");
	const cases: [string[], RegExp][] = [
		[["--script", leads, "--repo", repo, "--bogus"], /^Unknown option '--bogus'\. Usage: /u],
		[["--script", leads, "--repo", repo, "extra"], /^Unexpected argument 'extra'\. Usage: /u],
		[["--repo", repo, "--script"], /^Option '--script <value>' argument missing\. Usage: /u],
		[
			["--script", leads, "--repo", path.join(root, "absent")],
			/^--repo: \S+absent is not a folder$/u,
		],
		[
			["--script", leads, "--repo", path.join(root, "change.diff")],
			/^--repo: .* is not a folder$/u,
		],
		[["--script", leads, "--repo", repo, "--context", path.join(root, "absent")], /^--context: /u],
		[["--script", path.join(root, "absent.ts"), "--repo", repo], /^--script: ENOENT/u],
		[
			["--script", leads, "--repo", repo, "--diff", path.join(root, "absent.diff")],
			/^--diff: ENOENT/u,
		],
		[
			["--script", leads, "--repo", repo, "--diff", path.join(root, "empty.diff")],
			/^--diff: \S+empty\.diff holds no unified diff$/u,
		],
		[
			["--script", leads, "--repo", repo, "--diff", path.join(root, "notes.diff")],
			/^--diff: \S+notes\.diff holds no unified diff$/u,
		],
		[["--script", leads, "--repo", repo, "--out", path.join(root, "list.json")], /^--out: EEXIST/u],
		[
			["--script", leads, "--repo", repo, "--metadata", path.join(root, "broken.json")],
			/^--metadata: \S+broken\.json is not JSON: /u,
		],
		[
			["--script", leads, "--repo", repo, "--metadata", path.join(root, "list.json")],
			/^--metadata: \S+list\.json must hold a JSON object$/u,
		],
	];
	for (const [args, message] of cases) {
		const { status, stdout, stderr } = run(args);
		assert.equal(status, 2, `${args.join(" ")}\n${stderr}`);
		assert.equal(stdout, "", args.join(" "));
		assert.match(stderr.trimEnd(), message, args.join(" "));
		assert.equal(stderr.trimEnd().split("\n").length, 1, stderr);
	}
});
