/** Exercise the chart's PostgreSQL policy and one worker surge in a disposable, loopback-only fixture. */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { setTimeout as pause } from "node:timers/promises";
import { fileURLToPath } from "node:url";

import { Client } from "pg";
import { parse } from "yaml";

import { asArray, asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const root = fileURLToPath(new URL("../", import.meta.url));
const profile = asRecord(
	parse(readFileSync(path.join(root, "deploy/chart/hephaestus/profiles/small.yaml"), "utf8")),
	"small profile",
);
const defaults = asRecord(
	parse(readFileSync(path.join(root, "deploy/chart/hephaestus/values.yaml"), "utf8")),
	"chart defaults",
);
const policy = asRecord(asRecord(profile.postgresql, "postgresql").parameters, "parameters");
const base = asString(
	/^FROM (?<image>\S+) AS os-packages$/mu.exec(
		readFileSync(path.join(root, "docker/postgres/Dockerfile"), "utf8"),
	)?.groups?.image,
	"pinned PostgreSQL base",
);
assert.ok(base.includes("@sha256:"), "the fixture uses the image's pinned PostgreSQL base");
const name = `heph-small-pg-${randomUUID()}`;
const clients: Client[] = [];
const connectionErrors: string[] = [];
let sampler: ReturnType<typeof setInterval> | undefined;
let samplingError: unknown;

function docker(...args: string[]) {
	const result = spawnSync("docker", args, {
		encoding: "utf8",
		timeout: 180_000,
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	assert.equal(result.status, 0, result.stderr);
	return result.stdout.trim();
}

const positiveInteger = (value: unknown) => {
	assert.equal(typeof value, "number");
	assert.ok(Number.isSafeInteger(value) && Number(value) > 0);
	return Number(value);
};
const maximum = (role: string) =>
	positiveInteger(
		asRecord(
			asRecord((role === "migration" ? defaults : profile)[role], role).databasePool,
			"databasePool",
		).maximumPoolSize,
	);

function memory() {
	const raw = docker(
		"exec",
		name,
		"sh",
		"-c",
		"cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events /sys/fs/cgroup/memory.stat",
	);
	const lines = raw.split("\n");
	const counters = new Map<string, number>();
	for (const line of lines.slice(2)) {
		const [key, value] = line.split(" ");
		counters.set(asString(key, "cgroup counter"), Number(value));
	}
	const counter = (key: string) => {
		const value = counters.get(key);
		assert.ok(value !== undefined && Number.isFinite(value), `missing cgroup counter ${key}`);
		return value;
	};
	const current = Number(lines[0]);
	const peak = Number(lines[1]);
	assert.ok(
		Number.isFinite(current) && Number.isFinite(peak) && peak > 0,
		"cgroup v2 counters are required",
	);
	return {
		current,
		peak,
		anon: counter("anon"),
		file: counter("file"),
		shmem: counter("shmem"),
		kernel: counter("kernel"),
		oomKill: counter("oom_kill"),
	};
}

function planNodes(value: unknown): Record<string, unknown>[] {
	const node = asRecord(value, "plan node");
	return [
		node,
		...(node.Plans === undefined ? [] : asArray(node.Plans, "child plans").flatMap(planNodes)),
	];
}

try {
	docker(
		"run",
		"--detach",
		"--name",
		name,
		"--memory=2g",
		"--memory-swap=2g",
		"--cpus=2",
		"--publish",
		"127.0.0.1::5432",
		"--mount",
		`type=bind,source=${path.join(root, "docker/postgres/size-from-memory.sh")},target=/policy-entrypoint,readonly`,
		"--entrypoint",
		"/policy-entrypoint",
		"--env",
		"POSTGRES_PASSWORD=fixture-only",
		base,
		...Object.entries(policy).flatMap(([key, value]) => ["-c", `${key}=${String(value)}`]),
	);
	let ready = false;
	for (let attempt = 0; attempt < 60; attempt += 1) {
		if (
			spawnSync("docker", ["exec", name, "pg_isready", "--host=127.0.0.1", "--username=postgres"], {
				timeout: 5000,
				maxBuffer: CAPTURE_LIMIT_BYTES,
			}).status === 0
		) {
			ready = true;
			break;
		}
		await pause(1000);
	}
	assert.ok(ready, "PostgreSQL starts with the small policy");
	const address = /^127\.0\.0\.1:(?<port>\d+)$/u.exec(docker("port", name, "5432/tcp"));
	const port = Number(asString(address?.groups?.port, "loopback-only database port"));
	const connect = async (label: string, user = "cap2_client") => {
		const client = new Client({
			host: "127.0.0.1",
			port,
			user,
			password: "fixture-only",
			database: "postgres",
			application_name: label,
			connectionTimeoutMillis: 5000,
			statement_timeout: 120_000,
		});
		client.on("error", (error) => {
			connectionErrors.push(error.message);
		});
		await client.connect();
		clients.push(client);
		return client;
	};
	const observer = await connect("cap2-observer", "postgres");
	const expected: Record<string, string> = {
		shared_buffers: "32768",
		work_mem: "2048",
		hash_mem_multiplier: "1",
		max_connections: "80",
		maintenance_work_mem: "65536",
		autovacuum_work_mem: "32768",
		autovacuum_max_workers: "3",
		effective_cache_size: "131072",
		max_parallel_workers_per_gather: "0",
		max_wal_size: "1024",
		autovacuum: "on",
		track_counts: "on",
		fsync: "on",
		full_page_writes: "on",
		synchronous_commit: "on",
		shared_preload_libraries: "pg_stat_statements",
		superuser_reserved_connections: "3",
		reserved_connections: "0",
	};
	const showResult = await observer.query<Record<string, unknown>>("SHOW ALL");
	const show = Object.fromEntries(
		showResult.rows
			.filter((row) => typeof row.name === "string" && row.name in expected)
			.map((row) => [asString(row.name, "SHOW name"), asString(row.setting, "SHOW value")]),
	);
	for (const [key, value] of Object.entries(policy)) {
		assert.equal(show[key], String(value), `SHOW ${key}`);
	}
	const settingsResult = await observer.query<Record<string, unknown>>(
		"SELECT name, setting, unit, source FROM pg_settings WHERE name=ANY($1)",
		[Object.keys(expected)],
	);
	const settings = Object.fromEntries(
		settingsResult.rows.map((row) => [asString(row.name, "setting name"), row]),
	);
	for (const [key, value] of Object.entries(expected)) {
		const setting = asRecord(settings[key], key);
		assert.equal(setting.setting, value, key);
		if (key in policy) {
			assert.equal(setting.source, "command line", `${key} overrides the calculator`);
		}
	}
	await observer.query(
		"CREATE ROLE cap2_client LOGIN PASSWORD 'fixture-only'; CREATE TABLE cap2_input AS SELECT g AS id, repeat(md5(g::text),4) AS payload FROM generate_series(1,300000) g; ANALYZE cap2_input; GRANT SELECT ON cap2_input TO cap2_client",
	);
	const initialMemory = memory();
	const groups = new Map<string, Client[]>();
	const counts = {
		server: maximum("server"),
		"worker-old": maximum("worker"),
		"worker-new": maximum("worker"),
		listeners: 3,
		migration: maximum("migration"),
		backup: 2,
		operator: 4,
	};
	for (const [role, count] of Object.entries(counts)) {
		groups.set(
			role,
			await Promise.all(Array.from({ length: count }, async () => connect(`cap2-${role}`))),
		);
	}
	const countResult = await observer.query<Record<string, unknown>>(
		"SELECT application_name, count(*)::int AS connections FROM pg_stat_activity WHERE application_name LIKE 'cap2-%' AND application_name<>'cap2-observer' GROUP BY application_name",
	);
	const observed = Object.fromEntries(
		countResult.rows.map((row) => [
			asString(row.application_name, "application name").replace(/^cap2-/u, ""),
			positiveInteger(row.connections),
		]),
	);
	assert.deepEqual(observed, counts);
	const roleConnections = Object.values(observed).reduce((total, count) => total + count, 0);
	const reserved = Number(settings.superuser_reserved_connections?.setting);
	assert.ok(roleConnections + reserved <= Number(settings.max_connections?.setting));
	const loadClients = [
		...(groups.get("worker-old") ?? []).slice(0, 4),
		...(groups.get("worker-new") ?? []).slice(0, 4),
	];
	assert.equal(loadClients.length, 8);
	const snapshots = [initialMemory, memory()];
	sampler = setInterval(() => {
		try {
			snapshots.push(memory());
		} catch (error) {
			samplingError = error;
		}
	}, 500);
	const queries = [
		"SELECT sum(length(payload)) FROM (SELECT payload FROM cap2_input ORDER BY payload) ordered",
		"SELECT count(*) FROM (SELECT id % 50000 AS bucket, count(*) FROM cap2_input GROUP BY bucket) grouped",
	];
	const plans = await Promise.all(
		loadClients.map(async (client, index) => {
			const result = await client.query<Record<string, unknown>>(
				`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${asString(queries[index % queries.length], "workload query")}`,
			);
			const document = asRecord(
				asArray(result.rows[0]?.["QUERY PLAN"], "EXPLAIN")[0],
				"EXPLAIN result",
			);
			const nodes = planNodes(document.Plan);
			return {
				executionMs: document["Execution Time"],
				operators: nodes
					.filter(
						(node) => node["Sort Method"] !== undefined || node["Peak Memory Usage"] !== undefined,
					)
					.map((node) => ({
						type: node["Node Type"],
						sortMethod: node["Sort Method"],
						sortSpaceType: node["Sort Space Type"],
						sortSpaceKiB: node["Sort Space Used"],
						memoryKiB: node["Peak Memory Usage"],
						diskKiB: node["Disk Usage"],
						tempReadBlocks: node["Temp Read Blocks"],
						tempWrittenBlocks: node["Temp Written Blocks"],
					})),
			};
		}),
	);
	clearInterval(sampler);
	sampler = undefined;
	assert.equal(samplingError, undefined, "memory sampling succeeds during the loaded queries");
	snapshots.push(memory());
	assert.ok(
		plans.some((plan) => plan.operators.some((operator) => operator.sortSpaceType === "Disk")),
		"the loaded sort must exceed work_mem and spill",
	);
	assert.ok(
		snapshots.every((sample) => sample.oomKill === initialMemory.oomKill),
		"no cgroup OOM kill",
	);
	assert.ok(
		snapshots.at(-1)?.peak !== undefined && Number(snapshots.at(-1)?.peak) < 2 * 1024 ** 3,
		"the fixture stays inside the retained 2 GiB limit",
	);
	assert.deepEqual(connectionErrors, []);
	process.stdout.write(
		`${JSON.stringify({ image: base, limits: { memoryBytes: 2 * 1024 ** 3, cpus: 2 }, show, settings, connections: { observed, roleConnections, superuserReserved: reserved, observer: 1, webhook: "verified separately by the application role integration tests" }, inputRows: 300_000, simultaneousQueries: loadClients.length, plans, memory: snapshots, qualification: "Controlled SQL/connection fixture only. Representative capture, vacuum, WAL archive, Hikari latency and node capacity qualification remain separate gates; the PostgreSQL ceiling stays 2 GiB." }, null, 2)}\n`,
	);
} finally {
	if (sampler !== undefined) {
		clearInterval(sampler);
	}
	await Promise.allSettled(clients.map(async (client) => client.end()));
	const removed = spawnSync("docker", ["rm", "--force", "--volumes", name], {
		encoding: "utf8",
		timeout: 30_000,
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	assert.equal(removed.status, 0, "the fixture removes its own container and volume");
}
