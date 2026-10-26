/**
 * `gate:shell`: the pinned ShellCheck on every shell script of deploy/platform, the scripts that an
 * operator runs on a host without Node.
 */
import { readdir } from "node:fs/promises";
import path from "node:path";

import { run } from "./lib/process.ts";
import { prepareShellCheck } from "./lib/shellcheck.ts";

const platform = path.join(import.meta.dirname, "..", "deploy", "platform");
const entries = await readdir(platform);
const scripts = entries
	.filter((name) => name.endsWith(".sh"))
	.map((name) => path.join(platform, name));
if (scripts.length === 0) {
	throw new Error(`no shell script in ${platform}`);
}
await run(await prepareShellCheck(), ["--norc", "--shell=sh", "--severity=style", ...scripts]);
