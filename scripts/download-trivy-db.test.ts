import assert from "node:assert/strict";
import { spawnSync, type SpawnSyncReturns } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { parseDocument } from "yaml";

void test(
	"prepares Java metadata only when requested and fails if it cannot be prepared",
	{
		skip: process.platform === "win32",
	},
	async (context) => {
		const action = parseDocument(
			await readFile(".github/actions/download-trivy-db/action.yml", "utf8"),
		);
		const script: unknown = action.getIn(["runs", "steps", 2, "run"]);
		if (typeof script !== "string") throw new Error("Database action has no executable step");
		const directory = await mkdtemp(path.join(tmpdir(), "trivy-download-"));
		context.after(() => rm(directory, { recursive: true, force: true }));
		await writeFile(
			path.join(directory, "trivy"),
			`#!/bin/sh
printf '%s\\n' "$*" >> "$SCAN_LOG"
case "$*" in *--download-java-db-only*) exit "$JAVA_EXIT" ;; esac
`,
			{ mode: 0o755 },
		);
		await writeFile(path.join(directory, "sleep"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
		const log = path.join(directory, "calls");
		for (const [javaDb, exit, expectedCalls] of [
			["false", "0", 1],
			["true", "0", 2],
			["true", "17", 4],
			["invalid", "0", 0],
		] as const) {
			await writeFile(log, "");
			const env = {
				...process.env,
				PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				DATABASE_ARTIFACT: "registry",
				JAVA_DB: javaDb,
				JAVA_EXIT: exit,
				MAX_AGE_HOURS: "",
				METADATA_PATH: "",
				SCAN_LOG: log,
			};
			const expectedSuccess = exit === "0" && javaDb !== "invalid";
			const { status, stderr }: SpawnSyncReturns<string> = spawnSync("bash", ["-c", script], {
				encoding: "utf8",
				env,
			});
			assert.equal(status === 0, expectedSuccess, stderr);
			const calls = (await readFile(log, "utf8")).trim().split("\n").filter(Boolean);
			assert.equal(calls.length, expectedCalls);
			if (expectedCalls > 0) assert.match(calls[0] ?? "", /--download-db-only$/);
			for (const call of calls.slice(1)) assert.match(call, /--download-java-db-only$/);
		}
	},
);

void test(
	"restored snapshots never fetch new advisories and still enforce freshness and Java preparation",
	{ skip: process.platform === "win32" },
	async (context) => {
		const action = parseDocument(
			await readFile(".github/actions/download-trivy-db/action.yml", "utf8"),
		);
		const validate: unknown = action.getIn(["runs", "steps", 0, "run"]);
		const prepare: unknown = action.getIn(["runs", "steps", 2, "run"]);
		assert.ok(typeof validate === "string");
		assert.ok(typeof prepare === "string");
		const directory = await mkdtemp(path.join(tmpdir(), "trivy-snapshot-"));
		context.after(() => rm(directory, { recursive: true, force: true }));
		const db = path.join(directory, "db");
		await mkdir(db);
		const log = path.join(directory, "calls");
		await writeFile(
			path.join(directory, "trivy"),
			'#!/bin/sh\nprintf "%s\\n" "$*" >> "$SCAN_LOG"\n',
			{ mode: 0o755 },
		);
		for (const scenario of [
			{ id: "123", java: "false", age: 1, file: true, success: true },
			{ id: "123", java: "true", age: 1, file: true, success: true },
			{ id: "123", java: "false", age: 25, file: true, success: false },
			{ id: "123", java: "false", age: -1, file: true, success: false },
			{ id: "123", java: "false", age: 1, file: false, success: false },
			{ id: "", java: "false", age: 1, file: true, success: false },
			{ id: "0", java: "false", age: 1, file: true, success: false },
			{ id: "123,456", java: "false", age: 1, file: true, success: false },
		]) {
			await writeFile(log, "");
			const output = path.join(directory, "output");
			await writeFile(output, "");
			await writeFile(
				path.join(db, "metadata.json"),
				JSON.stringify({
					UpdatedAt: new Date(Date.now() - scenario.age * 3_600_000).toISOString(),
				}),
			);
			if (scenario.file) await writeFile(path.join(db, "trivy.db"), "the immutable database");
			else await rm(path.join(db, "trivy.db"), { force: true });
			const result: SpawnSyncReturns<string> = spawnSync(
				"bash",
				["-e", "-c", `${validate}\n${prepare}`],
				{
					encoding: "utf8",
					env: {
						...process.env,
						PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
						TRIVY_CACHE_DIR: directory,
						DATABASE_ARTIFACT: scenario.id,
						JAVA_DB: scenario.java,
						MAX_AGE_HOURS: "24",
						METADATA_PATH: "",
						GITHUB_OUTPUT: output,
						SCAN_LOG: log,
					},
				},
			);
			assert.equal(
				result.status === 0,
				scenario.success,
				`${JSON.stringify(scenario)}: ${result.stderr}`,
			);
			assert.equal(
				await readFile(output, "utf8"),
				scenario.id === "123" ? `directory=${db}\n` : "",
			);
			const calls = await readFile(log, "utf8");
			assert.doesNotMatch(calls, /--download-db-only/);
			assert.equal(calls.includes("--download-java-db-only"), scenario.java === "true");
			if (scenario.file)
				assert.equal(await readFile(path.join(db, "trivy.db"), "utf8"), "the immutable database");
		}
		assert.equal(
			action.getIn(["runs", "steps", 1, "with", "artifact-ids"]),
			`\${{ inputs.database-artifact }}`,
		);
		assert.equal(
			action.getIn(["runs", "steps", 1, "with", "path"]),
			`\${{ steps.cache.outputs.directory }}`,
		);
		assert.equal(action.getIn(["runs", "steps", 1, "with", "merge-multiple"]), true);
		assert.equal(action.getIn(["runs", "steps", 1, "continue-on-error"]), undefined);
	},
);
