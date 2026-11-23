// Starts the packaged server image as the chart's small profile runs it, and fails when a fact that the
// image's JVM settings depend on no longer holds (ADR 0067 § Confirmation):
// - the AOT cache loads under the chart's JVM options (`-XX:AOTMode=on` exits when it cannot),
// - the server becomes ready no slower than the base commit's image, started in the same job,
// - a run without the cache loads fewer classes than the memory calculator's class count, with a margin,
//   and keeps Metaspace below its share of the cap.
//
// Usage: node scripts/server-image-startup.ts <server-image> <postgres-image> <base-server-image>

import { spawnSync } from "node:child_process";
import { randomInt, randomUUID } from "node:crypto";
import { appendFileSync } from "node:fs";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";
import { pathToFileURL } from "node:url";

import {
	componentOf,
	container,
	envValue,
	get,
	layersFor,
	render,
	workload,
} from "./kubernetes-chart-render-support.ts";
import { serverBuildEnvironment } from "./lib/buildpack-descriptor.ts";
import { applyDatabaseRoles, databaseRolePasswords } from "./lib/database-roles.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import { sample } from "./lib/prometheus-text.ts";

/**
 * The calculator's class count must exceed what a run without the cache loads by this factor. This run has an empty
 * database. On 2026-11-17, staging loaded 44,905 classes, and this run loaded about 38,250 of the same image (×1.17).
 * So the margin that production keeps is small, and the Metaspace alert covers the rest.
 */
export const CLASS_MARGIN = 1.2;
/** The share of the Metaspace cap that a run without the cache may use after the warm-up. */
export const METASPACE_SHARE = 0.85;
/**
 * The most that the image's fastest start may exceed the base image's fastest start on the same runner. Hosted
 * runners differ in speed by more than a startup regression that matters, so the job compares two images on one
 * runner instead of one image against a fixed number of seconds. Contention only adds time, so each image's
 * fastest start is its estimate. The limit is the 99.5th percentile of the ratio when both images are the same,
 * with a margin; docs/contributor/ci-cd.mdx has the measurement.
 */
export const READY_RATIO_LIMIT = 1.25;
const WARM_UP_SECONDS = 60;
const READY_TIMEOUT_MS = 240_000;
/** No request may hold the job: an accepted connection that never answers fails the run. */
const REQUEST_TIMEOUT_MS = 10_000;
const SYSTEM_KEY = "startupTestEncryptionKey01234567";
const CREDENTIAL_KEY = "startupTestCredentialKey01234567";
const PUBLIC_PATHS = ["/api/identity-providers", "/api/auth/csrf", "/api/.well-known/jwks.json"];

const [serverImage = "", postgresImage = "", baseImage = ""] = process.argv.slice(2);
const runId = `startup-${randomUUID().slice(0, 8)}`;
const network = runId;
// Metrics answer only on the management listener's own address, so each server gets a fixed address on a
// network of its own, and the runner reaches it there.
const subnet = `10.231.${String(randomInt(256))}`;
const postgres = `${runId}-postgres`;
const credentials = databaseRolePasswords();
/** Every container that this run started, removed at its end. */
const started = [postgres];

function docker(...args: string[]): string {
	const result = spawnSync("docker", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
	if (result.status !== 0) {
		throw new Error(`docker ${args[0] ?? ""} failed:\n${result.stdout}${result.stderr}`);
	}
	return (args[0] === "logs" ? result.stdout + result.stderr : result.stdout).trim();
}

/** A container's CPU and memory limits as `docker run` takes them. */
interface Limits {
	readonly cpus: string;
	readonly memory: string;
}

/** What the chart's small profile gives the server and the migrate Job: options, calculator inputs and limits. */
interface Chart {
	readonly javaToolOptions: string;
	readonly calculator: readonly string[];
	readonly server: Limits;
	readonly migration: Limits;
}

function limitsOf(containerSpec: unknown): Limits {
	const limits = get(containerSpec, "resources", "limits");
	const memory = String(get(limits, "memory"));
	const parsed = /^(?<amount>\d+)(?<unit>Gi|Mi)$/u.exec(memory)?.groups;
	if (parsed?.amount === undefined || parsed.unit === undefined) {
		throw new Error(`Unexpected memory limit ${memory}`);
	}
	const cpu = String(get(limits, "cpu"));
	return {
		cpus: cpu.endsWith("m") ? String(Number(cpu.slice(0, -1)) / 1000) : cpu,
		memory: `${parsed.amount}${parsed.unit === "Gi" ? "g" : "m"}`,
	};
}

/**
 * The calculator sizes the heap from the container's limit, and a heap over 32 GiB turns compressed oops off,
 * which the cache does not match. So each run gets the limits of the chart's container.
 */
export function chartOf(): Chart {
	const rendered = render(layersFor("small"));
	if (!rendered.ok) {
		throw new Error(`helm template failed:\n${rendered.stderr}`);
	}
	const server = workload(rendered.resources, "server");
	const javaToolOptions = envValue(server, "JAVA_TOOL_OPTIONS");
	if (typeof javaToolOptions !== "string") {
		throw new TypeError("The chart's server container has no literal JAVA_TOOL_OPTIONS");
	}
	const calculator = ["BPL_JVM_HEAD_ROOM", "BPL_JVM_THREAD_COUNT"].flatMap((name) => {
		const value = envValue(server, name);
		return typeof value === "string" ? ["--env", `${name}=${value}`] : [];
	});
	const job = rendered.resources.find(
		(resource) => resource.kind === "Job" && componentOf(resource) === "migration",
	);
	if (job === undefined) {
		throw new Error("The chart's small profile renders no migrate Job");
	}
	return {
		javaToolOptions,
		calculator,
		server: limitsOf(container(server)),
		migration: limitsOf(container(job)),
	};
}

/** The image that a run starts, and the database that this image's own migrations created. */
interface Subject {
	readonly image: string;
	readonly database: string;
}

function databaseEnvironment(database: string, migrationMode = false): string[] {
	return [
		"--env",
		"SPRING_LIQUIBASE_CONTEXTS=prod",
		"--env",
		`SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${database}`,
		"--env",
		`SPRING_DATASOURCE_USERNAME=${migrationMode ? "hephaestus_owner" : "hephaestus_app"}`,
		"--env",
		`SPRING_DATASOURCE_PASSWORD=${migrationMode ? credentials.DATABASE_OWNER_PASSWORD : credentials.DATABASE_APP_PASSWORD}`,
		...(migrationMode
			? ["--env", `SPRING_LIQUIBASE_PASSWORD=${credentials.DATABASE_OWNER_PASSWORD}`]
			: []),
		"--env",
		`HEPHAESTUS_SECURITY_ENCRYPTION_KEY=${SYSTEM_KEY}`,
		"--env",
		`HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=${CREDENTIAL_KEY}`,
	];
}

function migrate(chart: Chart, subject: Subject, label: string): void {
	const name = `${runId}-${label}`;
	started.push(name);
	// The migrate run mode sets no JVM options of its own, as the chart's migration Job does.
	const result = spawnSync(
		"docker",
		[
			"run",
			"--rm",
			"--name",
			name,
			"--network",
			network,
			"--cpus",
			chart.migration.cpus,
			"--memory",
			chart.migration.memory,
			"--env",
			"SPRING_PROFILES_ACTIVE=e2e,migrate",
			"--env",
			"JAVA_TOOL_OPTIONS=-XX:AOTMode=on",
			...databaseEnvironment(subject.database, true),
			subject.image,
		],
		{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES, timeout: 600_000 },
	);
	if (result.status !== 0) {
		throw new Error(
			`The migrate run mode of ${subject.image} exited with ${String(result.status ?? result.signal)}; with -XX:AOTMode=on, a rejected AOT cache stops it:\n${result.stdout}${result.stderr}`,
		);
	}
}

interface Run {
	readonly name: string;
	readonly exposition: string;
}

async function start(
	chart: Chart,
	subject: Subject,
	label: string,
	address: string,
	aotMode: "on" | "off",
	warmUp: boolean,
): Promise<Run> {
	const name = `${runId}-${label}`;
	started.push(name);
	docker(
		"run",
		"--detach",
		"--name",
		name,
		"--network",
		network,
		"--ip",
		address,
		"--cpus",
		chart.server.cpus,
		"--memory",
		chart.server.memory,
		"--env",
		"SPRING_PROFILES_ACTIVE=e2e",
		"--env",
		"SPRING_LIQUIBASE_ENABLED=false",
		"--env",
		`MANAGEMENT_SERVER_ADDRESS=${address}`,
		// The agent runtime starts, as with the chart's sandboxes. No cluster answers, so it starts no sandbox.
		"--env",
		"AGENT_ENABLED=true",
		"--env",
		"HEPHAESTUS_RUNTIME_WORKER_ENABLED=true",
		"--env",
		`HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE=${runId}`,
		"--env",
		"GIT_CHECKOUT_ENABLED=true",
		"--env",
		"HEPHAESTUS_FABRIC_ROOT=/tmp/hephaestus-git",
		"--env",
		"HEPHAESTUS_WORKSPACE_INIT_DEFAULT=false",
		"--env",
		"HEPHAESTUS_SYNC_RUN_ON_STARTUP=false",
		...chart.calculator,
		"--env",
		`JAVA_TOOL_OPTIONS=${chart.javaToolOptions} -XX:AOTMode=${aotMode}`,
		...databaseEnvironment(subject.database),
		subject.image,
	);
	const application = `http://${address}:8080`;
	const management = `http://${address}:9090`;
	const deadline = Date.now() + READY_TIMEOUT_MS;
	for (;;) {
		if (docker("inspect", "--format", "{{.State.Running}}", name) !== "true") {
			throw new Error(`${label} stopped before it was ready:\n${docker("logs", name)}`);
		}
		const ready = await fetch(`${application}/readyz`, {
			signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
		}).then(
			(response) => response.ok,
			() => false,
		);
		if (ready) {
			break;
		}
		if (Date.now() > deadline) {
			throw new Error(`${label} was not ready within ${String(READY_TIMEOUT_MS / 1000)} s`);
		}
		await sleep(500);
	}
	if (warmUp) {
		for (const path of PUBLIC_PATHS) {
			const response = await fetch(`${application}${path}`, {
				signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
			});
			await response.arrayBuffer();
		}
		// The schedulers start and their first runs load their classes.
		await sleep(WARM_UP_SECONDS * 1000);
	}
	const scrape = await fetch(`${management}/actuator/prometheus`, {
		signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
	});
	const exposition = await scrape.text();
	if (!scrape.ok || !exposition.includes("jvm_classes_loaded_classes")) {
		throw new Error(
			`${label}'s Prometheus endpoint answered ${String(scrape.status)}:\n${exposition.slice(0, 2000)}`,
		);
	}
	return { name, exposition };
}

/** The ratio of the image's fastest ready time to the base image's. */
export function readyRatio(image: readonly number[], base: readonly number[]): number {
	if (image.length === 0 || base.length === 0) {
		throw new Error("A ready-time ratio needs a start of each image");
	}
	return Math.min(...image) / Math.min(...base);
}

function seconds(values: readonly number[]): string {
	return values.map((value) => value.toFixed(1)).join(", ");
}

function report(lines: readonly string[]): void {
	console.log(lines.join("\n"));
	const summary = process.env.GITHUB_STEP_SUMMARY;
	if (summary !== undefined && summary !== "") {
		appendFileSync(summary, `${lines.join("\n")}\n`);
	}
}

async function main(): Promise<void> {
	const classCount = Number(serverBuildEnvironment().get("BPE_DEFAULT_BPL_JVM_LOADED_CLASS_COUNT"));
	if (!Number.isInteger(classCount) || classCount <= 0) {
		throw new Error("server/application/project.toml sets no BPL_JVM_LOADED_CLASS_COUNT default");
	}
	// Without the base image there is no ratio, so the job fails here and says why.
	const pulled = spawnSync("docker", ["pull", "--quiet", baseImage], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	if (pulled.status !== 0) {
		throw new Error(
			`The base image ${baseImage} could not be pulled, so the startup time has nothing to compare against. A push to main publishes it.\n${pulled.stdout}${pulled.stderr}`,
		);
	}
	const baseDigest = docker("image", "inspect", "--format", "{{index .RepoDigests 0}}", baseImage);
	const failures: string[] = [];
	try {
		docker("network", "create", "--subnet", `${subnet}.0/24`, network);
		docker(
			"run",
			"--detach",
			"--name",
			postgres,
			"--network",
			network,
			"--network-alias",
			"postgres",
			"--env",
			"POSTGRES_DB=hephaestus",
			"--env",
			"POSTGRES_USER=root",
			"--env",
			"POSTGRES_PASSWORD=root",
			postgresImage,
		);
		for (let attempt = 1; ; attempt += 1) {
			// The image's init scripts run on a server that listens on the socket only, then restart it, so only
			// the final server answers on TCP.
			const ready = spawnSync("docker", [
				"exec",
				postgres,
				"pg_isready",
				"--host=127.0.0.1",
				"--username=root",
			]);
			if (ready.status === 0) {
				break;
			}
			if (attempt === 60) {
				throw new Error("PostgreSQL did not become ready");
			}
			await sleep(1000);
		}
		const chart = chartOf();
		const image: Subject = { image: serverImage, database: "hephaestus" };
		// The base image migrates a database of its own: this image's schema may have renamed what the base reads.
		const base: Subject = { image: baseImage, database: "hephaestus_base" };
		docker("exec", postgres, "createdb", "--username=root", base.database);
		await applyDatabaseRoles(postgres, image.database, credentials);
		await applyDatabaseRoles(postgres, base.database, credentials);
		migrate(chart, image, "migrate");
		migrate(chart, base, "migrate-base");

		// The two images alternate, so a runner that slows down during the job slows both alike.
		const readyTimes: Record<"image" | "base", number[]> = { image: [], base: [] };
		for (const [subject, key, host] of [
			[image, "image", 10],
			[base, "base", 13],
			[base, "base", 14],
			[image, "image", 12],
		] as const) {
			const cached = await start(
				chart,
				subject,
				`cache-on-${String(host)}`,
				`${subnet}.${String(host)}`,
				"on",
				false,
			);
			docker("stop", cached.name);
			readyTimes[key].push(sample(cached.exposition, "application_ready_time_seconds"));
		}
		const ratio = readyRatio(readyTimes.image, readyTimes.base);
		if (ratio > READY_RATIO_LIMIT) {
			failures.push(
				`The server's fastest start was ${ratio.toFixed(2)} times the base image's on this runner (${seconds(readyTimes.image)} s against ${seconds(readyTimes.base)} s), over the limit of ${String(READY_RATIO_LIMIT)}.`,
			);
		}

		const uncached = await start(chart, image, "cache-off", `${subnet}.11`, "off", true);
		docker("stop", uncached.name);
		const classes = sample(uncached.exposition, "jvm_classes_loaded_classes");
		const metaspaceUsed = sample(uncached.exposition, "jvm_memory_used_bytes", 'id="Metaspace"');
		const metaspaceMax = sample(uncached.exposition, "jvm_memory_max_bytes", 'id="Metaspace"');
		if (classes * CLASS_MARGIN > classCount) {
			failures.push(
				`A run without the AOT cache loaded ${String(classes)} classes. With the margin of ${String(CLASS_MARGIN)}, that exceeds BPE_DEFAULT_BPL_JVM_LOADED_CLASS_COUNT=${String(classCount)} in server/application/project.toml. Raise it.`,
			);
		}
		if (metaspaceUsed > METASPACE_SHARE * metaspaceMax) {
			failures.push(
				`A run without the AOT cache used ${(metaspaceUsed / 2 ** 20).toFixed(0)} MiB of Metaspace, over ${String(METASPACE_SHARE * 100)} % of its ${(metaspaceMax / 2 ** 20).toFixed(0)} MiB cap.`,
			);
		}
		report([
			"| Server image startup | Value | Bound |",
			"| --- | --- | --- |",
			`| Ready with the AOT cache (s) | ${seconds(readyTimes.image)} | |`,
			`| Base image ready with the AOT cache (s), \`${baseDigest}\` | ${seconds(readyTimes.base)} | |`,
			`| Fastest ready time against the base image's | ${ratio.toFixed(2)} | ≤ ${String(READY_RATIO_LIMIT)} |`,
			`| Classes loaded without the cache | ${String(classes)} | × ${String(CLASS_MARGIN)} ≤ ${String(classCount)} |`,
			`| Metaspace without the cache (MiB) | ${(metaspaceUsed / 2 ** 20).toFixed(0)} | ≤ ${String(METASPACE_SHARE * 100)} % of ${(metaspaceMax / 2 ** 20).toFixed(0)} |`,
		]);
	} finally {
		spawnSync("docker", ["rm", "--force", "--volumes", ...started], { stdio: "ignore" });
		spawnSync("docker", ["network", "rm", network], { stdio: "ignore" });
	}
	if (failures.length > 0) {
		throw new Error(failures.join("\n"));
	}
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
	if (serverImage === "" || postgresImage === "" || baseImage === "") {
		throw new Error(
			"Usage: node scripts/server-image-startup.ts <server-image> <postgres-image> <base-server-image>",
		);
	}
	await main();
}
