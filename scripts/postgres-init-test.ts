import { spawnSync } from "node:child_process";
import { randomBytes, randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

// PostgreSQL 18's postmaster restarts every backend when a child it did not start exits non-zero.
// As PID 1 it inherits such children from exec sessions, for example the pg_isready of a probe
// that timed out. This drill shows that the image's init reaps them, that an unclean stop restarts,
// and that an image whose postmaster ran as PID 1 upgrades to this one after an unclean stop.

const { values } = parseArgs({ options: { "target-image": { type: "string" } } });
const targetImage = values["target-image"];
if (targetImage !== undefined && targetImage.trim() === "") {
	throw new Error("--target-image must name an existing local PostgreSQL image");
}
const id = `postgres-init-${randomUUID().slice(0, 8)}`;
const image = targetImage ?? `${id}:18`;
const container = `${id}-db`;
const volume = `${id}-data`;
const password = randomBytes(16).toString("hex");
// The chart's user. The entrypoint then initializes the volume directly, without gosu.
const user = "999:999";

function docker(...args: string[]): string {
	const result = spawnSync("docker", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
	if (result.status !== 0) {
		throw new Error(`docker ${args.join(" ")} failed:\n${result.stdout}${result.stderr}`);
	}
	return result.stdout.trim();
}

function ready(): boolean {
	const probe = spawnSync("docker", ["exec", container, "pg_isready", "-q", "-h", "127.0.0.1"], {
		stdio: "ignore",
	});
	return probe.status === 0;
}

async function waitReady(step: string): Promise<void> {
	const deadline = Date.now() + 90_000;
	while (!ready()) {
		if (Date.now() > deadline) {
			throw new Error(`${step}: PostgreSQL is not ready after 90 s:\n${logs()}`);
		}
		await sleep(1000);
	}
}

function logs(): string {
	const result = spawnSync("docker", ["logs", container], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	return `${result.stdout}${result.stderr}`;
}

function start(entrypoint?: string): void {
	docker(
		"run",
		"-d",
		"--name",
		container,
		"--user",
		user,
		"-e",
		`POSTGRES_PASSWORD=${password}`,
		"-v",
		`${volume}:/var/lib/postgresql`,
		...(entrypoint === undefined ? [image] : ["--entrypoint", entrypoint, image, "postgres"]),
	);
}

function killAndRemove(): void {
	docker("kill", "--signal", "KILL", container);
	docker("rm", container);
}

try {
	if (targetImage === undefined) {
		docker("build", "-t", image, "docker/postgres");
	} else {
		docker("image", "inspect", targetImage);
	}
	docker("volume", "create", volume);
	docker(
		"run",
		"--rm",
		"--user",
		"0",
		"--entrypoint",
		"chown",
		"-v",
		`${volume}:/var/lib/postgresql`,
		image,
		"999:999",
		"/var/lib/postgresql",
	);

	// The entrypoint before this fix: the postmaster is PID 1. An entrypoint override drops the
	// image's command, so it names postgres again. A SIGKILL leaves its lock file,
	// which names PID 1, as a node power loss does.
	start("size-from-memory.sh");
	await waitReady("the PID 1 postmaster");
	killAndRemove();

	// The image's entrypoint starts on that data directory.
	start();
	await waitReady("the upgrade after an unclean stop");
	const init = docker("exec", container, "cat", "/proc/1/comm");
	if (init !== "tini") {
		throw new Error(`PID 1 is ${init}, not tini`);
	}

	// An orphan that exits non-zero, as a killed probe's pg_isready does.
	docker("exec", container, "sh", "-c", "(sleep 1; exit 2) &");
	await sleep(4000);
	if (/untracked child process|terminating any other active server processes/u.test(logs())) {
		throw new Error(`PostgreSQL treated an orphan as a crashed backend:\n${logs()}`);
	}
	if (!ready()) {
		throw new Error(`PostgreSQL stopped accepting connections after an orphan exited:\n${logs()}`);
	}

	// An unclean stop of the image's own postmaster.
	docker("kill", "--signal", "KILL", container);
	docker("start", container);
	await waitReady("the restart after an unclean stop");
	console.log("PostgreSQL init drill passed");
} finally {
	spawnSync("docker", ["rm", "-f", container], { stdio: "ignore" });
	spawnSync("docker", ["volume", "rm", "-f", volume], { stdio: "ignore" });
	if (targetImage === undefined) {
		spawnSync("docker", ["rmi", "-f", image], { stdio: "ignore" });
	}
}
