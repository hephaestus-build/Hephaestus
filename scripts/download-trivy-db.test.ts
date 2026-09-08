import assert from "node:assert/strict";
import { spawnSync, type SpawnSyncReturns } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
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
		const script: unknown = action.getIn(["runs", "steps", 0, "run"]);
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
