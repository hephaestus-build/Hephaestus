/**
 * `gate:shell`: the pinned ShellCheck on every tracked `.sh` script and the Git hooks. These run
 * where Node is not available: on an operator's host, in a container image, or in a chart Job.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

import { output, run } from "./lib/process.ts";
import { prepareShellCheck } from "./lib/shellcheck.ts";

const root = path.join(import.meta.dirname, "..");
const files = (
	await output("git", ["ls-files", "-z", "--", "*.sh", ".vite-hooks/*"], { cwd: root })
)
	.split("\0")
	.filter(Boolean);
if (files.length === 0) {
	throw new Error("no tracked shell script");
}
// A script without a shebang is one the chart runs with `sh`.
const byShell = new Map<string, string[]>();
for (const file of files) {
	const shebang = (await readFile(path.join(root, file), "utf8")).split("\n", 1)[0] ?? "";
	const shell = /^#!.*\bbash\b/u.test(shebang) ? "bash" : "sh";
	byShell.set(shell, [...(byShell.get(shell) ?? []), file]);
}
const shellcheck = await prepareShellCheck();
for (const [shell, scripts] of byShell) {
	await run(shellcheck, ["--norc", `--shell=${shell}`, "--severity=style", ...scripts], {
		cwd: root,
	});
}
