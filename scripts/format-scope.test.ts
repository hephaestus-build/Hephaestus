import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { test } from "node:test";

import { repositoryCli } from "./lib/process.ts";

// A bare `vp fmt` reaches every file that `.oxfmtrc.json` does not ignore, and agents and editors
// run it on whatever they touched. Each file it reaches is either formatted or deliberately ignored:
// golden prompts, provider webhook fixtures, generated clients and specs, released contracts and
// mirrored skills are compared byte for byte, so a reflow breaks them.
void test("a bare format run changes no file", () => {
	const result = spawnSync(process.execPath, [repositoryCli(), "fmt", "--check", "."], {
		cwd: path.join(import.meta.dirname, ".."),
		encoding: "utf8",
	});
	assert.equal(
		result.status,
		0,
		`${result.stdout}${result.stderr}\nRun \`vp run format\`. For a file compared byte for byte or generated, add it to .oxfmtrc.json ignorePatterns.`,
	);
});
