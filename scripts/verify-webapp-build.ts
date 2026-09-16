import { spawnSync } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";

const routeTree = "webapp/src/routeTree.gen.ts";
const before = await readFile(routeTree);
let status = 1;
let changed = false;
try {
	status = spawnSync("vp", ["run", "build:webapp"], { stdio: "inherit" }).status ?? 1;
	if (status === 0) {
		changed = !before.equals(await readFile(routeTree));
	}
} finally {
	await writeFile(routeTree, before);
}
if (status !== 0) {
	process.exit(status);
}
if (changed) {
	throw new Error("webapp/src/routeTree.gen.ts is stale; run `vp run build:webapp`");
}
