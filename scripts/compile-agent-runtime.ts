import { spawnSync } from "node:child_process";
import { rm } from "node:fs/promises";
import path from "node:path";

const output = process.argv[2];
if (output === undefined) {
	throw new Error("Expected the agent runtime output directory");
}
await rm(output, { recursive: true, force: true });
const result = spawnSync(
	process.execPath,
	[
		path.resolve("node_modules/typescript/bin/tsc"),
		"-p",
		path.join(import.meta.dirname, "../tsconfig.agent-runtime.json"),
		"--outDir",
		output,
	],
	{ stdio: "inherit" },
);
if (result.error !== undefined) {
	throw result.error;
}
process.exitCode = result.status ?? 1;
