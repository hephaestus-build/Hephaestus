import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	existsSync,
	mkdtempSync,
	realpathSync,
	readFileSync,
	rmSync,
	statSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { setTimeout } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const bootstrap = fileURLToPath(
	new URL("../../../main/resources/agent/pi-precompute.sh", import.meta.url),
);
const fileLimit = 10 * 1024 * 1024;

void test("production precompute limits", { skip: process.platform !== "linux" }, async (t) => {
	for (const scenario of [
		{
			name: "synchronous loop",
			code: 'writeFileSync(output + "/.complete", "partial"); while (true) {}',
			failure: true,
		},
		{
			name: "hung module initialization",
			code: "await new Promise(() => { setInterval(() => {}, 1000); });",
			failure: true,
		},
		{
			name: "oversized result",
			code: 'writeFileSync(output + "/large.json", Buffer.alloc(11 * 1024 * 1024));',
			failure: true,
		},
		{ name: "log flood", code: "writeFileSync(1, Buffer.alloc(11 * 1024 * 1024));", failure: true },
		{
			name: "write outside grant",
			code: 'writeFileSync(root + "/task.json", "overwrite");',
			failure: true,
		},
		{
			name: "valid output and clean environment",
			code: 'if (process.env.PRECOMPUTE_TEST_SECRET || process.env.NODE_OPTIONS) throw Error("inherited environment"); writeFileSync(output + "/ok", "ok");',
			failure: false,
		},
		{
			name: "background child after success",
			code: `const { spawn } = await import("node:child_process"); spawn(process.execPath, ["-e", 'setTimeout(() => require("node:fs").writeFileSync(' + JSON.stringify(output + "/leaked") + ', "leaked"), 500)'], { stdio: "ignore" }).unref();`,
			failure: false,
		},
	]) {
		await t.test(scenario.name, async () => {
			// Resolved for the same reason as in `pi-precompute.spec.ts`: the runner is loaded as a
			// module under a path granted to `--allow-fs-read`, and a `TMPDIR` reached through a symlink
			// satisfies no such grant.
			const root = realpathSync(mkdtempSync(join(tmpdir(), "precompute-limits-")));
			try {
				writeFileSync(
					join(root, "pi-precompute.ts"),
					`import { writeFileSync } from "node:fs";\nconst root = process.argv[2]; const output = root + "/work/precompute-out";\n${scenario.code}\n`,
				);
				const result = spawnSync(
					"sh",
					[
						"-c",
						'sh "$1" 1 "$2" && "$3" -e \'require("node:fs").writeFileSync(process.argv[1] + "/review-output", Buffer.alloc(10 * 1024 * 1024 + 1))\' "$2" && printf "review-continues"',
						"sh",
						bootstrap,
						root,
						process.execPath,
					],
					{
						env: { ...process.env, PRECOMPUTE_TEST_SECRET: "test-only" },
						encoding: "utf8",
						timeout: 5000,
					},
				);
				assert.equal(result.error, undefined);
				assert.equal(result.status, 0, result.stderr);
				assert.equal(result.stdout, "review-continues");
				assert.equal(
					result.stderr.includes("continuing without hints"),
					scenario.failure,
					result.stderr,
				);
				assert.ok(statSync(join(root, "work/precompute-runner.log")).size <= fileLimit);
				if (scenario.failure) {
					assert.ok(statSync(join(root, "work/precompute-out/precompute-runner.log")).size <= 8192);
					assert.equal(existsSync(join(root, "work/precompute-out/.complete")), false);
					assert.equal(existsSync(join(root, "work/precompute-out/large.json")), false);
				}
				if (scenario.name === "valid output and clean environment")
					assert.equal(readFileSync(join(root, "work/precompute-out/ok"), "utf8"), "ok");
				if (scenario.name === "background child after success") {
					await setTimeout(750);
					assert.equal(existsSync(join(root, "work/precompute-out/leaked")), false);
				}
				// The precompute subshell must not impose its file limit on the review that follows.
				assert.equal(statSync(join(root, "review-output")).size, fileLimit + 1);
			} finally {
				rmSync(root, { recursive: true, force: true });
			}
		});
	}
});
