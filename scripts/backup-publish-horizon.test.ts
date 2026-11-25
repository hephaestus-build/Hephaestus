import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const parser = fileURLToPath(
	new URL("../deploy/chart/hephaestus/files/backup-oldest-point.pl", import.meta.url),
);
const olderBackup = 1_795_020_000;
const inventory = {
	name: "hephaestus",
	status: { code: 0, message: "ok" },
	backup: [
		{ type: "diff", timestamp: { start: 1_795_482_000, stop: 1_795_482_010 } },
		{ type: "full", timestamp: { start: olderBackup, stop: 1_795_020_030 } },
	],
};

function parse(stanza: object) {
	const result = spawnSync("perl", [parser], {
		encoding: "utf8",
		input: JSON.stringify([stanza]),
		timeout: 10_000,
	});
	assert.ifError(result.error);
	return result;
}

await test("the reporter reads the oldest backup in the retained inventory", () => {
	const result = parse(inventory);
	assert.equal(result.status, 0, result.stderr);
	assert.equal(result.stdout, `${olderBackup}\n`);
});

await test("an invalid status cannot produce a backup horizon", () => {
	for (const code of [undefined, false, "unavailable", 1]) {
		const result = parse({ ...inventory, status: { code } });
		assert.notEqual(result.status, 0);
		assert.equal(result.stdout, "");
	}
});

await test(
	"a stalled reporter ends within its deadline and preserves the backup exit status",
	{ skip: process.platform === "win32" },
	async (t) => {
		// The test database runs the postmaster as PID 1. A killed orphan can trigger crash recovery.
		// In production the reporter runs in a separate Job.
		const dir = await mkdtemp(path.join(tmpdir(), "backup-horizon-"));
		t.after(async () => {
			await rm(dir, { recursive: true, force: true });
		});
		await writeFile(
			path.join(dir, "pgbackrest"),
			'#!/bin/sh\nprintf "%s\n" "Backup ran"\nexit 23\n',
			{
				mode: 0o755,
			},
		);
		await writeFile(
			path.join(dir, "psql"),
			"#!/bin/sh\nprintf '%s\n' 'Reporter ran'\ntrap '' TERM\nexec sleep 10\n",
			{
				mode: 0o755,
			},
		);
		// Linux runs the same GNU timeout as the backup Job. On macOS the fixture needs only Node.
		const deadline =
			process.platform === "linux"
				? 'exec /usr/bin/timeout --kill-after=1s 1s "$@"'
				: `exec "$BACKUP_TEST_NODE" -e '
const { spawnSync } = require("node:child_process");
const child = spawnSync(process.argv[1], process.argv.slice(2), {
	stdio: "inherit",
	timeout: 1000,
	killSignal: "SIGKILL",
});
process.exit(child.error?.code === "ETIMEDOUT" ? 124 : child.status ?? 1);
' "$@"`;
		await writeFile(
			path.join(dir, "timeout"),
			`#!/bin/sh
set -eu
test "$1" = --kill-after=5s
test "$2" = 60s
test "$3" = psql
shift 2
${deadline}
`,
			{ mode: 0o755 },
		);
		const script = fileURLToPath(
			new URL("../deploy/chart/hephaestus/files/backup-publish-horizon.sh", import.meta.url),
		);
		const result = spawnSync("sh", [script, "diff"], {
			env: {
				...process.env,
				BACKUP_TEST_NODE: process.execPath,
				PATH: [dir, process.env.PATH ?? ""].join(path.delimiter),
			},
			encoding: "utf8",
			timeout: 15_000,
		});
		assert.ifError(result.error);
		assert.equal(result.status, 23, result.stderr);
		assert.match(result.stdout, /Backup ran\s+Reporter ran/u);
		assert.match(result.stderr, /Cannot publish the PostgreSQL recovery point/u);
	},
);
