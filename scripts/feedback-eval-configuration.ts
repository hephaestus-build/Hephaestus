/**
 * Writes the system configuration of one arm (`ichnaea.configuration/1`) of the feedback eval decision:
 * scripts/feedback-eval/ichnaea-configuration.ts owns its content.
 * Usage: node scripts/feedback-eval-configuration.ts --out <file> [--root <checkout>] [--commit <sha>]
 * The commit defaults to the checkout's HEAD.
 */
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { parseArgs } from "node:util";

import { composerConfiguration } from "./feedback-eval/ichnaea-configuration.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const { values } = parseArgs({
	options: { out: { type: "string" }, commit: { type: "string" }, root: { type: "string" } },
});
if (values.out === undefined) {
	throw new Error("--out is required");
}
const root = values.root ?? ".";
const commit =
	values.commit ??
	execFileSync("git", ["rev-parse", "HEAD"], {
		cwd: root,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	}).trim();
writeFileSync(
	values.out,
	`${JSON.stringify(composerConfiguration(root, process.env, commit), null, "\t")}\n`,
);
