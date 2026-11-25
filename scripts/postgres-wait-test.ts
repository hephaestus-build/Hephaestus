/**
 * Proves the PostgreSQL wait that the drills and CI steps share: it answers only for the final server.
 *
 * An init script holds the entrypoint's temporary server open. That server answers on the socket, as a socket
 * `pg_isready` shows. The wait must not pass while it runs. Once the wait passes, the database that the init created
 * takes connections.
 *
 * Usage: node scripts/postgres-wait-test.ts --target-image <image>
 */
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { finalServerAnswers, waitForFinalServer } from "./lib/postgres-container.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const { values } = parseArgs({ options: { "target-image": { type: "string" } } });
const image = values["target-image"];
if (image === undefined) {
	throw new Error("Usage: node scripts/postgres-wait-test.ts --target-image <image>");
}
const container = `postgres-wait-${randomUUID().slice(0, 8)}`;

function docker(...args: string[]): string {
	const result = spawnSync("docker", args, {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		timeout: 60_000,
		killSignal: "SIGKILL",
	});
	if (result.status !== 0) {
		throw new Error(
			`docker ${args.join(" ")} ended with ${String(result.status)}:\n${result.stdout}${result.stderr}`,
		);
	}
	return result.stdout.trim();
}

function succeeds(...args: string[]): boolean {
	return (
		spawnSync("docker", args, { stdio: "ignore", timeout: 15_000, killSignal: "SIGKILL" })
			.status === 0
	);
}

const scripts = mkdtempSync(path.join(tmpdir(), "postgres-wait-"));
try {
	// Runs on the temporary server, after it created POSTGRES_DB.
	writeFileSync(path.join(scripts, "hold.sh"), "touch /tmp/init-holding\nsleep 20\n");
	docker(
		"create",
		"--name",
		container,
		"--env",
		"POSTGRES_DB=hephaestus",
		"--env",
		"POSTGRES_USER=root",
		"--env",
		"POSTGRES_PASSWORD=root",
		image,
	);
	docker("cp", path.join(scripts, "hold.sh"), `${container}:/docker-entrypoint-initdb.d/hold.sh`);
	docker("start", container);

	let holding = false;
	for (let attempt = 0; attempt < 60 && !holding; attempt += 1) {
		holding =
			succeeds("exec", container, "test", "-f", "/tmp/init-holding") &&
			succeeds("exec", container, "pg_isready", "--quiet");
		if (!holding) {
			await sleep(1000);
		}
	}
	if (!holding) {
		throw new Error("The init script never ran on a temporary server that answers on the socket");
	}
	if (finalServerAnswers(container)) {
		throw new Error("The wait answered for the temporary init server, which shuts down next");
	}

	await waitForFinalServer(container);
	const answer = docker(
		"exec",
		container,
		"psql",
		"--username=root",
		"--dbname=hephaestus",
		"-Atc",
		"SELECT 1",
	);
	if (answer !== "1") {
		throw new Error(`The final server answered the wait but not a query: ${answer}`);
	}
	console.log("The wait passed only for the final server.");
} finally {
	spawnSync("docker", ["rm", "--force", container], { stdio: "ignore", timeout: 60_000 });
	rmSync(scripts, { recursive: true, force: true });
}
