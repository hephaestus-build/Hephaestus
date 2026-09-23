import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";

const { values } = parseArgs({ options: { "target-image": { type: "string" } } });
const id = `pgbackrest-pitr-${randomUUID().slice(0, 8)}`;
const image = values["target-image"] ?? `${id}:18`;
const container = `${id}-db`;
const objectStore = `${id}-s3`;
const network = `${id}-network`;
const volumes = [`${id}-data`, `${id}-socket`, `${id}-objects`] as const;
const [data, socket, objects] = volumes;
const config = path.join(import.meta.dirname, "..", "docker", "self-host", "pgbackrest.conf");
const certificates = mkdtempSync(path.join(tmpdir(), `${id}-certs-`));
const minioImage =
	"quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e";
const repositoryEnvironment = (restored: boolean): string[] => [
	"-e",
	"PGBACKREST_REPO1_TYPE=s3",
	"-e",
	"PGBACKREST_REPO1_S3_BUCKET=hephaestus",
	"-e",
	"PGBACKREST_REPO1_S3_ENDPOINT=minio",
	"-e",
	"PGBACKREST_REPO1_S3_REGION=us-east-1",
	"-e",
	"PGBACKREST_REPO1_S3_URI_STYLE=path",
	"-e",
	"PGBACKREST_REPO1_STORAGE_PORT=9000",
	"-e",
	"PGBACKREST_REPO1_STORAGE_CA_FILE=/etc/pgbackrest/minio.crt",
	"-e",
	`PGBACKREST_REPO1_S3_KEY=${restored ? "restore-reader" : "backup-writer"}`,
	"-e",
	`PGBACKREST_REPO1_S3_KEY_SECRET=${restored ? "local-reader-secret" : "local-writer-secret"}`,
	"-e",
	"PGBACKREST_REPO1_CIPHER_PASS=local-test-only-passphrase-1234567890",
];

function run(command: string, args: string[], input?: Buffer): string {
	const result = spawnSync(command, args, {
		encoding: "utf8",
		input,
		maxBuffer: 64 * 1024 * 1024,
		timeout: 6 * 60 * 1000,
	});
	if (result.status !== 0) {
		throw new Error(`${command} failed:\n${result.stdout}${result.stderr}`);
	}
	return result.stdout.trim();
}

function docker(...args: string[]): string {
	return run("docker", args);
}

function pause(seconds: number): void {
	Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, seconds * 1000);
}

function waitForObjectStore(): void {
	for (let attempt = 0; attempt < 60; attempt += 1) {
		if (
			spawnSync("docker", [
				"exec",
				objectStore,
				"curl",
				"-kfsS",
				"https://localhost:9000/minio/health/live",
			]).status === 0
		) {
			return;
		}
		pause(1);
	}
	throw new Error(`S3 test service did not become ready:\n${docker("logs", objectStore)}`);
}

function startObjectStore(): void {
	writeFileSync(
		path.join(certificates, "restore-policy.json"),
		JSON.stringify({
			Version: "2012-10-17",
			Statement: [
				{
					Effect: "Allow",
					Action: ["s3:ListBucket", "s3:GetBucketLocation"],
					Resource: ["arn:aws:s3:::hephaestus"],
				},
				{
					Effect: "Allow",
					Action: ["s3:GetObject"],
					Resource: ["arn:aws:s3:::hephaestus/*"],
				},
			],
		}),
	);
	run("openssl", [
		"req",
		"-x509",
		"-newkey",
		"rsa:2048",
		"-nodes",
		"-days",
		"1",
		"-subj",
		"/CN=minio",
		"-addext",
		"subjectAltName=DNS:minio",
		"-keyout",
		path.join(certificates, "private.key"),
		"-out",
		path.join(certificates, "public.crt"),
	]);
	docker("network", "create", network);
	docker(
		"run",
		"-d",
		"--name",
		objectStore,
		"--network",
		network,
		"--network-alias",
		"minio",
		"-e",
		"MINIO_ROOT_USER=backup-writer",
		"-e",
		"MINIO_ROOT_PASSWORD=local-writer-secret",
		"-v",
		`${objects}:/data`,
		"-v",
		`${certificates}:/root/.minio/certs:ro`,
		minioImage,
		"server",
		"/data",
	);
	waitForObjectStore();
	docker(
		"exec",
		objectStore,
		"mc",
		"alias",
		"set",
		"--insecure",
		"local",
		"https://localhost:9000",
		"backup-writer",
		"local-writer-secret",
	);
	docker("exec", objectStore, "mc", "mb", "--insecure", "local/hephaestus");
	docker(
		"exec",
		objectStore,
		"mc",
		"admin",
		"user",
		"add",
		"--insecure",
		"local",
		"restore-reader",
		"local-reader-secret",
	);
	docker(
		"exec",
		objectStore,
		"mc",
		"admin",
		"policy",
		"create",
		"--insecure",
		"local",
		"restore-reader",
		"/root/.minio/certs/restore-policy.json",
	);
	docker(
		"exec",
		objectStore,
		"mc",
		"admin",
		"policy",
		"attach",
		"--insecure",
		"local",
		"restore-reader",
		"--user",
		"restore-reader",
	);
}

function sql(query: string): string {
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

function waitForDatabase(): void {
	for (let attempt = 0; attempt < 60; attempt += 1) {
		if (
			spawnSync("docker", ["exec", container, "sh", "-c", 'test "$(cat /proc/1/comm)" = postgres'])
				.status === 0 &&
			spawnSync("docker", ["exec", container, "pg_isready", "-U", "root", "-d", "hephaestus"])
				.status === 0
		) {
			return;
		}
		pause(1);
	}
	throw new Error(`restored PostgreSQL did not become ready:\n${docker("logs", container)}`);
}

function archiveCurrentWal(): void {
	const segment = sql("SELECT pg_walfile_name(pg_current_wal_lsn())");
	sql("SELECT pg_switch_wal()");
	for (let attempt = 0; attempt < 60; attempt += 1) {
		if (
			sql(
				`SELECT (pg_stat_file('pg_wal/archive_status/${segment}.done', true)).size IS NOT NULL`,
			) === "t"
		) {
			return;
		}
		pause(1);
	}
	throw new Error(`WAL segment ${segment} was not archived within 60 seconds`);
}

function start(restored = false): number {
	docker(
		"run",
		"-d",
		"--name",
		container,
		"--network",
		network,
		"-p",
		"127.0.0.1::5432",
		"-e",
		"POSTGRES_DB=hephaestus",
		"-e",
		"POSTGRES_USER=root",
		"-e",
		"POSTGRES_PASSWORD=root",
		...repositoryEnvironment(restored),
		"-v",
		`${data}:/var/lib/postgresql`,
		"-v",
		`${socket}:/var/run/postgresql`,
		"-v",
		`${config}:/etc/pgbackrest/pgbackrest.conf:ro`,
		"-v",
		`${path.join(certificates, "public.crt")}:/etc/pgbackrest/minio.crt:ro`,
		image,
		"postgres",
		"-c",
		`archive_mode=${restored ? "off" : "on"}`,
		...(restored
			? []
			: [
					"-c",
					"archive_timeout=60s",
					"-c",
					"archive_command=pgbackrest --stanza=hephaestus archive-push %p",
				]),
	);
	waitForDatabase();
	const mapping = docker("port", container, "5432/tcp");
	return Number(mapping.slice(mapping.lastIndexOf(":") + 1));
}

function sidecar(dataMode: "ro" | "rw", restored: boolean, ...args: string[]): string {
	return docker(
		"run",
		"--rm",
		"--network",
		network,
		"--user",
		"postgres",
		...repositoryEnvironment(restored),
		"-v",
		`${data}:/var/lib/postgresql:${dataMode}`,
		"-v",
		`${socket}:/var/run/postgresql`,
		"-v",
		`${config}:/etc/pgbackrest/pgbackrest.conf:ro`,
		"-v",
		`${path.join(certificates, "public.crt")}:/etc/pgbackrest/minio.crt:ro`,
		"--entrypoint",
		"pgbackrest",
		image,
		"--stanza=hephaestus",
		...args,
	);
}

try {
	if (values["target-image"] === undefined) {
		docker("build", "-t", image, "docker/postgres");
	} else {
		docker("image", "inspect", image);
	}
	for (const volume of volumes) {
		docker("volume", "create", volume);
	}
	startObjectStore();
	const sourcePort = start();
	run("node", [
		"scripts/run-gradlew.ts",
		":application:liquibaseUpdate",
		...(process.env.CI === "true" ? ["-PpackagedServer=true"] : []),
		`-PpostgresPort=${sourcePort}`,
		"--quiet",
	]);
	docker("exec", "-u", "postgres", container, "pgbackrest", "--stanza=hephaestus", "stanza-create");
	docker("exec", "-u", "postgres", container, "pgbackrest", "--stanza=hephaestus", "check");
	sql("UPDATE instance_settings SET silent_mode_engaged = FALSE WHERE id = 1");
	sql(
		"INSERT INTO workspace(account_login, account_type, display_name, is_publicly_viewable, slug, status, mentor_enabled) VALUES ('restore-probe', 'USER', 'Restore probe', FALSE, 'restore-probe', 'ACTIVE', TRUE)",
	);
	sql(
		"CREATE TABLE restore_probe(value text PRIMARY KEY); INSERT INTO restore_probe VALUES ('before')",
	);
	sidecar("ro", false, "--type=full", "backup");
	sidecar("ro", true, "verify");
	docker(
		"exec",
		objectStore,
		"mc",
		"alias",
		"set",
		"--insecure",
		"reader",
		"https://localhost:9000",
		"restore-reader",
		"local-reader-secret",
	);
	if (
		spawnSync("docker", [
			"exec",
			objectStore,
			"mc",
			"cp",
			"--insecure",
			"/etc/hosts",
			"reader/hephaestus/forbidden-write",
		]).status === 0
	) {
		throw new Error("restore identity was able to write to the backup bucket");
	}
	docker("stop", objectStore);
	let outageFailed = false;
	try {
		sidecar("ro", false, "--io-timeout=5", "--type=diff", "backup");
	} catch {
		outageFailed = true;
	} finally {
		docker("start", objectStore);
		waitForObjectStore();
	}
	if (!outageFailed) {
		throw new Error("backup did not fail when the off-host repository was unavailable");
	}
	sidecar("ro", true, "verify");
	sql("INSERT INTO restore_probe VALUES ('middle')");
	archiveCurrentWal();
	pause(2);
	const target = `${new Date().toISOString().slice(0, 19).replace("T", " ")}+00`;
	pause(2);
	sql("INSERT INTO restore_probe VALUES ('after')");
	archiveCurrentWal();
	docker("rm", "-f", container);
	docker("volume", "rm", data);
	docker("volume", "create", data);
	const restoreStartedAt = performance.now();
	sidecar("rw", true, "--type=time", `--target=${target}`, "--target-action=promote", "restore");
	start(true);
	const restoreSeconds = ((performance.now() - restoreStartedAt) / 1000).toFixed(1);
	if (sql("SELECT string_agg(value, ',' ORDER BY value) FROM restore_probe") !== "before,middle") {
		throw new Error("PITR did not replay pre-target WAL and exclude the post-target write");
	}
	if (Number(sql("SELECT count(*) FROM databasechangelog")) < 1) {
		throw new Error("restored database has no application migration history");
	}
	const lockdown = readFileSync(
		path.join(import.meta.dirname, "..", "docker", "self-host", "restore-clone-lockdown.sql"),
	);
	run(
		"docker",
		["exec", "-i", container, "psql", "-U", "root", "-d", "hephaestus", "-v", "ON_ERROR_STOP=1"],
		lockdown,
	);
	if (sql("SELECT silent_mode_engaged FROM instance_settings WHERE id = 1") !== "t") {
		throw new Error("restored instance did not engage Silent Mode");
	}
	if (
		sql(
			"SELECT practice_delivery_status || ':' || mentor_enabled FROM workspace WHERE slug = 'restore-probe'",
		) !== "PAUSED:false"
	) {
		throw new Error("restored workspace did not pause feedback delivery and mentor");
	}
	console.log(
		`PITR passed: encrypted S3 repository verified; read-only restore and backup outage proved; restored target ${target} in ${restoreSeconds}s; pre-target WAL present; post-target row absent; application data locked down`,
	);
} finally {
	spawnSync("docker", ["rm", "-f", container]);
	spawnSync("docker", ["rm", "-f", objectStore]);
	for (const volume of volumes) {
		spawnSync("docker", ["volume", "rm", "-f", volume]);
	}
	spawnSync("docker", ["network", "rm", network]);
	rmSync(certificates, { recursive: true, force: true });
	if (values["target-image"] === undefined) {
		spawnSync("docker", ["rmi", "-f", image]);
	}
}
