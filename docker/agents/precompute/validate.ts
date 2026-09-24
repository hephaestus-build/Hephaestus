#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { cp, mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
/**
 * Validate precompute scripts WITHOUT the sandbox: runs each script over a real repo + diff exactly as the
 * runner does, checks the PracticeResult shape, and prints the metrics/directions/hints so you can eyeball
 * that the feature extraction is meaningful.
 *
 * Usage: node validate.ts --repo <clone> [--diff <patch>] [--scripts <dir>] [--metadata <json>]
 *   --scripts defaults to ../../../server/application/src/main/resources/practices/precompute (the version-controlled home)
 */
import { parseArgs } from "node:util";

import { globFilesSync } from "./lib/files.ts";

import { parsePracticeResult } from "./lib/practice-contract.ts";
import type { PracticeResult } from "./lib/types.ts";

const DEFAULT_SCRIPTS_DIR = path.resolve(
	import.meta.dirname,
	"../../../server/application/src/main/resources/practices/precompute",
);

const { values } = parseArgs({
	args: process.argv.slice(2),
	options: {
		repo: { type: "string" },
		diff: { type: "string" },
		metadata: { type: "string" },
		context: { type: "string" },
		scripts: { type: "string", default: DEFAULT_SCRIPTS_DIR },
	},
});
if (values.repo === undefined || values.repo === "") {
	console.error("--repo required");
	process.exit(2);
}

const scriptsDir = values.scripts;

const work = `/tmp/pc-validate.${process.pid}`;
await rm(work, { recursive: true, force: true });
await mkdir(`${work}/practices`, { recursive: true });
await writeFile(`${work}/package.json`, '{"type":"module"}\n');
await symlink(path.resolve(import.meta.dirname, "lib"), `${work}/lib`);
let n = 0;
for (const f of globFilesSync("*.ts", scriptsDir)) {
	await cp(path.join(scriptsDir, f), `${work}/practices/${f}`);
	n += 1;
}
console.error(`Validating ${n} script(s) from ${scriptsDir}`);

const args = [
	path.resolve(import.meta.dirname, "runner.ts"),
	"--repo",
	values.repo,
	"--output",
	work,
];
if (values.diff !== undefined && values.diff !== "") {
	args.push("--diff", values.diff);
}
if (values.metadata !== undefined && values.metadata !== "") {
	args.push("--metadata", values.metadata);
}
if (values.context !== undefined && values.context !== "") {
	args.push("--context", values.context);
}
const proc = spawnSync(process.execPath, args, { stdio: ["ignore", "ignore", "inherit"] });
if (proc.status !== 0) {
	console.error(`runner exited ${String(proc.status)}; results below may be partial or absent`);
}

let fail = 0;
for (const f of globFilesSync("*.json", work)) {
	let result: PracticeResult;
	try {
		result = parsePracticeResult(JSON.parse(await readFile(path.join(work, f), "utf8")), f);
	} catch (error) {
		fail += 1;
		console.log(`\n❌ ${f}  [${error instanceof Error ? error.message : String(error)}]`);
		continue;
	}

	const ok = result.status === "ok";
	if (!ok) {
		fail += 1;
	}
	console.log(`\n${ok ? "✅" : "❌"} ${result.practice}  [status=${result.status}]`);
	console.log(`   metrics: ${JSON.stringify(result.metrics)}`);
	for (const d of result.directions) {
		console.log(`   • ${d}`);
	}
	const [firstHint] = result.hints;
	if (firstHint) {
		console.log(
			`   hints: ${result.hints.length} (e.g. ${firstHint.file}:${firstHint.line} ${firstHint.pattern})`,
		);
	}
}
console.log(`\n${fail === 0 ? "ALL SCRIPTS VALID" : `${fail} SCRIPT(S) INVALID`}`);
process.exit(fail === 0 ? 0 : 1);
