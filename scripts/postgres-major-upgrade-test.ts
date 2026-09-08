// Rehearses the operator upgrade documented in docs/admin/backup-restore.mdx § PostgreSQL 17 to 18:
// dump the PostgreSQL 17 cluster, destroy the volume, recreate it under the same stable name, and
// restore into a fresh PostgreSQL 18 cluster. It also proves the safety property ADR 0038's
// amendment leans on — PostgreSQL 18 refuses to start against PostgreSQL 17 data instead of coming
// up healthy and empty.
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { parseArgs } from "node:util";

const { values } = parseArgs({ options: { "target-image": { type: "string" } } });
const targetImage = values["target-image"];
if (targetImage !== undefined && targetImage.trim() === "")
	throw new Error("--target-image must name an existing local PostgreSQL image");

const id = `postgres-upgrade-${randomUUID().slice(0, 8)}`;
const source = `${id}-17`;
const target = `${id}-18`;
// One volume, reused across the major upgrade — the same stable `postgresql-data` name the shipped
// compose files declare (Compose prefixes it with the project name).
const volume = `${id}_postgresql-data`;

function run(command: string, args: string[], input?: Buffer): string {
	const result = spawnSync(command, args, { encoding: "utf8", input, maxBuffer: 64 * 1024 * 1024 });
	if (result.status !== 0) {
		throw new Error(`${command} ${args.join(" ")} failed:\n${result.stdout}${result.stderr}`);
	}
	return result.stdout.trim();
}

function docker(...args: string[]): string {
	return run("docker", args);
}

function sql(container: string, query: string): string {
	return docker(
		"exec",
		container,
		"psql",
		"-U",
		"root",
		"-d",
		"hephaestus",
		"-v",
		"ON_ERROR_STOP=1",
		"-Atc",
		query,
	);
}

function wait(container: string): void {
	for (let attempt = 0; attempt < 60; attempt++) {
		const result = spawnSync("docker", [
			"exec",
			container,
			"psql",
			"-U",
			"root",
			"-d",
			"hephaestus",
			"-c",
			"SELECT 1",
		]);
		if (result.status === 0) return;
		Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1_000);
	}
	throw new Error(`${container} did not become ready`);
}

function start(container: string, dataVolume: string, mount: string, image: string): number {
	docker(
		"run",
		"-d",
		"--name",
		container,
		"-p",
		"127.0.0.1::5432",
		"-v",
		`${dataVolume}:${mount}`,
		"-e",
		"POSTGRES_DB=hephaestus",
		"-e",
		"POSTGRES_USER=root",
		"-e",
		"POSTGRES_PASSWORD=root",
		image,
	);
	wait(container);
	const mapping = docker("port", container, "5432/tcp");
	return Number(mapping.slice(mapping.lastIndexOf(":") + 1));
}

function fingerprint(container: string): string {
	return sql(
		container,
		"SELECT count(*) || ':' || md5(string_agg(id || ':' || author || ':' || filename, '|' ORDER BY orderexecuted)) FROM databasechangelog",
	);
}

try {
	if (targetImage !== undefined) docker("image", "inspect", targetImage);
	run("docker", [
		"build",
		"--build-arg",
		"PG_MAJOR=17",
		"--build-arg",
		"PARTMAN_VERSION=5.4.3-1.pgdg12+1",
		"-t",
		`${id}:17`,
		"docker/postgres",
	]);
	if (targetImage === undefined) run("docker", ["build", "-t", `${id}:18`, "docker/postgres"]);
	docker("volume", "create", volume);

	const sourcePort = start(source, volume, "/var/lib/postgresql/data", `${id}:17`);
	if (sql(source, "SHOW server_version_num").slice(0, 2) !== "17")
		throw new Error("source is not PostgreSQL 17");

	run("node", [
		"scripts/run-mvnw.ts",
		"-f",
		"application/pom.xml",
		"liquibase:update",
		`-Dpostgres.port=${sourcePort}`,
		"--quiet",
	]);
	if (sql(source, "SELECT extversion FROM pg_extension WHERE extname='pg_partman'") !== "5.4.3") {
		throw new Error("source pg_partman is not 5.4.3");
	}
	sql(
		source,
		"CREATE TABLE upgrade_qualification(id bigint PRIMARY KEY, value text NOT NULL); INSERT INTO upgrade_qualification VALUES (1, 'preserved')",
	);
	sql(source, "CALL partman.run_maintenance_proc()");
	const sourceFingerprint = fingerprint(source);
	const partmanConfig = sql(
		source,
		"SELECT parent_table || ':' || partition_interval || ':' || premake || ':' || retention FROM partman.part_config WHERE parent_table='public.auth_event'",
	);
	if (!partmanConfig) throw new Error("auth_event is not registered with pg_partman");

	const dump = spawnSync("docker", ["exec", source, "pg_dump", "-U", "root", "-Fc", "hephaestus"], {
		maxBuffer: 64 * 1024 * 1024,
	}).stdout;
	if (!(dump instanceof Buffer) || dump.length === 0) throw new Error("source dump is empty");
	const listing = spawnSync("docker", ["exec", "-i", source, "pg_restore", "--list"], {
		input: dump,
	});
	if (listing.status !== 0) throw new Error("source dump is unreadable");
	docker("rm", "-f", source);

	// The safety property behind keeping the volume name stable: an operator who upgrades without
	// completing the dump-and-restore gets a container that refuses to start, not a silently empty
	// database. The 18+ entrypoint detects the foreign PG_VERSION and exits before initdb.
	const refusal = spawnSync(
		"docker",
		[
			"run",
			"--rm",
			"-v",
			`${volume}:/var/lib/postgresql`,
			"-e",
			"POSTGRES_DB=hephaestus",
			"-e",
			"POSTGRES_USER=root",
			"-e",
			"POSTGRES_PASSWORD=root",
			targetImage ?? `${id}:18`,
		],
		{ encoding: "utf8", timeout: 120_000, maxBuffer: 64 * 1024 * 1024 },
	);
	if (refusal.status === 0 || refusal.status === null) {
		throw new Error("PostgreSQL 18 did not refuse the PostgreSQL 17 data");
	}
	if (!refusal.stderr.includes("PostgreSQL data")) {
		throw new Error(`PostgreSQL 18 failed for an unexpected reason:\n${refusal.stderr}`);
	}

	// The operator's destructive step: the PostgreSQL 17 volume is removed and recreated under the
	// same name, so from here on the verified dump is the only copy of the data.
	docker("volume", "rm", volume);
	docker("volume", "create", volume);

	start(target, volume, "/var/lib/postgresql", targetImage ?? `${id}:18`);
	docker("exec", target, "dropdb", "-U", "root", "hephaestus");
	docker("exec", target, "createdb", "-U", "root", "hephaestus");
	const restore = spawnSync(
		"docker",
		[
			"exec",
			"-i",
			target,
			"pg_restore",
			"-U",
			"root",
			"-d",
			"hephaestus",
			"--no-owner",
			"--no-acl",
			"--single-transaction",
		],
		{ input: dump, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 },
	);
	if (restore.status !== 0) throw new Error(`restore failed:\n${restore.stdout}${restore.stderr}`);
	sql(target, "ALTER EXTENSION pg_partman UPDATE");

	if (sql(target, "SHOW server_version_num").slice(0, 2) !== "18")
		throw new Error("target is not PostgreSQL 18");
	if (sql(target, "SELECT extversion FROM pg_extension WHERE extname='pg_partman'") !== "5.5.0")
		throw new Error("target pg_partman is not 5.5.0");
	if (sql(target, "SELECT value FROM upgrade_qualification WHERE id=1") !== "preserved")
		throw new Error("qualification row was not restored");
	if (fingerprint(target) !== sourceFingerprint)
		throw new Error("Liquibase history changed during restore");
	if (
		sql(
			target,
			"SELECT parent_table || ':' || partition_interval || ':' || premake || ':' || retention FROM partman.part_config WHERE parent_table='public.auth_event'",
		) !== partmanConfig
	)
		throw new Error("pg_partman configuration changed during restore");
	sql(target, "CALL partman.run_maintenance_proc()");
	if (
		sql(
			target,
			"SELECT count(*) > 0 FROM pg_inherits WHERE inhparent = 'public.auth_event'::regclass",
		) !== "t"
	)
		throw new Error("auth_event partitions were not restored");
} finally {
	for (const container of [source, target]) spawnSync("docker", ["rm", "-f", container]);
	spawnSync("docker", ["volume", "rm", "-f", volume]);
	for (const image of targetImage === undefined ? [`${id}:17`, `${id}:18`] : [`${id}:17`])
		spawnSync("docker", ["rmi", "-f", image]);
}
