import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { rm } from "node:fs/promises";
import path from "node:path";

const output = process.argv[2];
const repository = path.resolve(import.meta.dirname, "..");
if (output === undefined || !path.isAbsolute(output)) {
	throw new Error("Expected an absolute runtime output directory inside the checkout");
}
const relative = path.relative(repository, output);
if (
	relative === "" ||
	relative === ".." ||
	relative.startsWith(`..${path.sep}`) ||
	path.isAbsolute(relative)
) {
	throw new Error("Expected an absolute runtime output directory inside the checkout");
}
const compiler = path.join(repository, "node_modules/typescript/bin/tsc");
if (!existsSync(compiler)) {
	throw new Error("Run pnpm install --frozen-lockfile before building the trusted runtime");
}
await rm(output, { recursive: true, force: true });
const result = spawnSync(
	process.execPath,
	[
		compiler,
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
