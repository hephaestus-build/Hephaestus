import { spawnSync } from "node:child_process";
import { randomBytes, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";

import { databaseMigration } from "./lib/database-migration.ts";
import { applyDatabaseRoles, databaseRolePasswords } from "./lib/database-roles.ts";
import { finalServerAnswers } from "./lib/postgres-container.ts";
import { bounded, howItEnded } from "./lib/process.ts";
import { conversationBindingsEnabled, seedRestoreProbe } from "./lib/restore-probe.ts";

const { values } = parseArgs({ options: { "target-image": { type: "string" } } });
const targetImage = values["target-image"];
if (targetImage !== undefined && targetImage.trim() === "") {
	throw new Error("--target-image must name an existing local PostgreSQL image");
}
const credentials = databaseRolePasswords();
const migrate = databaseMigration();
// The throwaway database lives for this run only.
const password = randomBytes(16).toString("hex");

const id = `postgres-restore-${randomUUID().slice(0, 8)}`;
const source = `${id}-source`;
const target = `${id}-target`;
const volume = `${id}_postgresql-data`;

function run(command: string, args: string[], input?: Buffer): string {
	const result = spawnSync(command, args, {
		encoding: "utf8",
		input,
		maxBuffer: 64 * 1024 * 1024,
		...bounded(6 * 60 * 1000),
	});
	if (result.status !== 0) {
		throw new Error(
			`${command} ${args.join(" ")} ${howItEnded(result)}:\n${result.stdout}${result.stderr}`,
		);
	}
	return result.stdout.trim();
}

function docker(...args: string[]): string {
	return run("docker", args);
}

function sql(container: string, query: string, user = "root"): string {
	return docker(
		"exec",
		container,
		"psql",
		"-U",
		user,
		"-d",
		"hephaestus",
		"-v",
		"ON_ERROR_STOP=1",
		"-Atc",
		query,
	);
}

function wait(container: string): void {
	for (let attempt = 0; attempt < 60; attempt += 1) {
		if (finalServerAnswers(container)) {
			return;
		}
		Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1000);
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
		// The image sizes its memory settings from this limit.
		"--memory",
		"2g",
		"-v",
		`${dataVolume}:${mount}`,
		"-e",
		"POSTGRES_DB=hephaestus",
		"-e",
		"POSTGRES_USER=root",
		"-e",
		`POSTGRES_PASSWORD=${password}`,
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
	if (targetImage === undefined) {
		run("docker", ["build", "-t", `${id}:18`, "docker/postgres"]);
	} else {
		docker("image", "inspect", targetImage);
	}
	docker("volume", "create", volume);

	const sourcePort = start(source, volume, "/var/lib/postgresql", targetImage ?? `${id}:18`);
	if (!sql(source, "SHOW server_version_num").startsWith("18")) {
		throw new Error("source is not PostgreSQL 18");
	}
	const sized = sql(
		source,
		"SELECT current_setting('shared_buffers') || ' ' || current_setting('max_connections') || ' ' || current_setting('shared_preload_libraries')",
	);
	if (sized !== "512MB 128 pg_stat_statements") {
		throw new Error(`source is not sized from its 2 GiB limit: ${sized}`);
	}

	applyDatabaseRoles(source, "hephaestus", credentials);
	migrate(sourcePort, credentials.DATABASE_OWNER_PASSWORD);
	if (sql(source, "SELECT extversion FROM pg_extension WHERE extname='pg_partman'") !== "5.5.0") {
		throw new Error("source pg_partman is not 5.5.0");
	}
	sql(
		source,
		"CREATE TABLE restore_qualification(id bigint PRIMARY KEY, value text NOT NULL); INSERT INTO restore_qualification VALUES (1, 'preserved')",
	);
	sql(source, "UPDATE instance_settings SET silent_mode_engaged = FALSE WHERE id = 1");
	sql(source, seedRestoreProbe);
	sql(source, "CALL partman.run_maintenance_proc()");
	const sourceFingerprint = fingerprint(source);
	const partmanConfig = sql(
		source,
		"SELECT parent_table || ':' || partition_interval || ':' || premake || ':' || retention FROM partman.part_config WHERE parent_table='public.auth_event'",
	);
	if (!partmanConfig) {
		throw new Error("auth_event is not registered with pg_partman");
	}

	const capture = spawnSync(
		"docker",
		["exec", source, "pg_dump", "-U", "root", "-Fc", "hephaestus"],
		{
			maxBuffer: 64 * 1024 * 1024,
			...bounded(6 * 60 * 1000),
		},
	);
	if (capture.status !== 0) {
		throw new Error(`backup ${howItEnded(capture)}:\n${capture.stderr.toString("utf8")}`);
	}
	const dump = capture.stdout;
	if (!(dump instanceof Buffer) || dump.length === 0) {
		throw new Error("source dump is empty");
	}
	const listing = spawnSync("docker", ["exec", "-i", source, "pg_restore", "--list"], {
		input: dump,
		...bounded(2 * 60 * 1000),
	});
	if (listing.status !== 0) {
		throw new Error(`source dump is unreadable: pg_restore --list ${howItEnded(listing)}`);
	}
	docker("rm", "-f", source);

	docker("volume", "rm", volume);
	docker("volume", "create", volume);

	start(target, volume, "/var/lib/postgresql", targetImage ?? `${id}:18`);
	docker("exec", target, "dropdb", "-U", "root", "hephaestus");
	docker("exec", target, "createdb", "-U", "root", "hephaestus");
	applyDatabaseRoles(target, "hephaestus", credentials);
	const restore = spawnSync(
		"docker",
		[
			"exec",
			"-i",
			target,
			"pg_restore",
			"-U",
			"hephaestus_owner",
			"-d",
			"hephaestus",
			"--no-owner",
			"--no-acl",
			"--single-transaction",
		],
		{ input: dump, encoding: "utf8", maxBuffer: 64 * 1024 * 1024, ...bounded(6 * 60 * 1000) },
	);
	if (restore.status !== 0) {
		throw new Error(`restore ${howItEnded(restore)}:\n${restore.stdout}${restore.stderr}`);
	}

	// A dump without database ACLs can restore the change-set history without the revocation.
	applyDatabaseRoles(target, "hephaestus", credentials);
	if (
		sql(
			target,
			"SELECT count(*) FROM pg_roles WHERE rolname IN ('hephaestus_owner', 'hephaestus_app', 'hephaestus_backup_reporter', 'hephaestus_monitor') AND has_database_privilege(oid, current_database(), 'TEMPORARY')",
		) !== "0"
	) {
		throw new Error("a restored Hephaestus role can create temporary tables");
	}

	if (!sql(target, "SHOW server_version_num").startsWith("18")) {
		throw new Error("target is not PostgreSQL 18");
	}
	if (sql(target, "SELECT extversion FROM pg_extension WHERE extname='pg_partman'") !== "5.5.0") {
		throw new Error("target pg_partman is not 5.5.0");
	}
	if (
		sql(
			target,
			"SELECT current_user || ':' || value FROM restore_qualification WHERE id=1",
			"hephaestus_app",
		) !== "hephaestus_app:preserved"
	) {
		throw new Error("qualification row was not restored");
	}
	if (fingerprint(target) !== sourceFingerprint) {
		throw new Error("Liquibase history changed during restore");
	}
	if (
		sql(
			target,
			"SELECT parent_table || ':' || partition_interval || ':' || premake || ':' || retention FROM partman.part_config WHERE parent_table='public.auth_event'",
		) !== partmanConfig
	) {
		throw new Error("pg_partman configuration changed during restore");
	}
	sql(target, "CALL partman.run_maintenance_proc()");
	if (
		sql(
			target,
			"SELECT count(*) > 0 FROM pg_inherits WHERE inhparent = 'public.auth_event'::regclass",
		) !== "t"
	) {
		throw new Error("auth_event partitions were not restored");
	}
	const lockdown = readFileSync(
		path.join(
			import.meta.dirname,
			"..",
			"deploy",
			"chart",
			"hephaestus",
			"files",
			"restore-clone-lockdown.sql",
		),
	);
	run(
		"docker",
		["exec", "-i", target, "psql", "-U", "root", "-d", "hephaestus", "-v", "ON_ERROR_STOP=1"],
		lockdown,
	);
	if (sql(target, "SELECT silent_mode_engaged FROM instance_settings WHERE id = 1") !== "t") {
		throw new Error("restored instance did not engage Silent Mode");
	}
	const restoredPolicy = sql(
		target,
		`SELECT practice_delivery_status || ':' || practice_rollout_revision || ':' || practice_review_event_enabled || ':' || practice_review_request_enabled || ':' || ${conversationBindingsEnabled} FROM workspace WHERE slug = 'restore-probe'`,
	);
	if (restoredPolicy !== "PAUSED:1:false:false:false") {
		throw new Error(`restored workspace did not pause all feedback paths: ${restoredPolicy}`);
	}
} finally {
	for (const container of [source, target]) {
		spawnSync("docker", ["rm", "-f", container], bounded(60_000));
	}
	spawnSync("docker", ["volume", "rm", "-f", volume], bounded(60_000));
	if (targetImage === undefined) {
		spawnSync("docker", ["rmi", "-f", `${id}:18`], bounded(60_000));
	}
}
