/**
 * Plants an `UnusedPrivateField` violation and runs `gate:server-lint` on it. The gate must miss its
 * cache and PMD must report that rule; a run that accepts the planted line is analysing something
 * other than the current sources, or replaying a verdict, and either is a silent green.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const REPO_ROOT = resolve(import.meta.dirname, "..");
const CANARY = resolve(
	REPO_ROOT,
	"server/application/src/main/java/de/tum/cit/aet/hephaestus/Application.java",
);
const original = readFileSync(CANARY, "utf8");
const planted = original.replace(
	"public class Application {",
	"public class Application {\n    private int deliberatelyUnusedPmdCanary;\n",
);
if (planted === original) throw new Error(`${CANARY} has no class body to plant the canary in`);

writeFileSync(CANARY, planted);
try {
	const pmd = spawnSync("vp", ["run", "gate:server-lint"], {
		cwd: REPO_ROOT,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	// The verdict is in PMD's report, which names the rule and the file; the console does not.
	const reportFile = resolve(REPO_ROOT, "server/application/build/reports/pmd/main.xml");
	if (!existsSync(reportFile)) {
		console.error(`${pmd.stdout}${pmd.stderr}`);
		throw new Error("PMD wrote no report; the build failed before analysis");
	}
	const flagged =
		pmd.status !== 0 &&
		/<file name="[^"]*Application\.java">[\s\S]*?rule="UnusedPrivateField"/.test(
			readFileSync(reportFile, "utf8"),
		);
	if (!flagged) {
		console.error(`${pmd.stdout}${pmd.stderr}`);
		throw new Error(
			"PMD accepted a planted UnusedPrivateField violation; it is not analysing the current sources",
		);
	}
	console.log("check-pmd-canary: PMD rejected the planted violation.");
} finally {
	writeFileSync(CANARY, original);
}
