import { access, readFile, rename, rm, writeFile } from "node:fs/promises";
import { join, relative } from "node:path";
import process from "node:process";

import { positivePort, readEnvFile } from "./lib/env.ts";
import { output, run, succeeds } from "./lib/process.ts";

const root = join(import.meta.dirname, "..");
const server = join(root, "server");
const dataDirectory = join(server, "postgres-data");
const changelogDirectory = join(server, "application/src/main/resources/db/changelog");
const master = join(server, "application/src/main/resources/db/master.xml");
// Liquibase writes the diff here (application/pom.xml, diffChangeLogFile); absent when there is no drift.
const draft = join(server, "application/target/changelog_new.xml");

interface Config {
	env: Record<string, string | undefined>;
	ci: boolean;
	host: string;
	port: number;
}

const log = (message: string): void => console.log(`ℹ️  ${message}`);

async function config(): Promise<Config> {
	const fileEnv = await readEnvFile(join(server, ".env"));
	const env = { ...fileEnv, ...process.env };
	return {
		env,
		ci: env.CI === "true",
		host: env.POSTGRES_HOST ?? "localhost",
		port: positivePort(env.POSTGRES_PORT ?? "5432", "POSTGRES_PORT"),
	};
}

async function checkEnvironment(value: Config): Promise<void> {
	await access(join(server, "pom.xml"));
	await access(join(import.meta.dirname, "generate-mermaid-erd.ts"));
	if (!value.ci) {
		if (!(await succeeds("docker", ["info"])))
			throw new Error("Docker is installed but unavailable. Start the Docker daemon, then retry.");
		if (!(await succeeds("docker", ["compose", "version"])))
			throw new Error("Docker Compose is required for local database utilities.");
	} else if (!(await succeeds("pg_isready", ["--version"]))) {
		throw new Error("CI database utilities require 'pg_isready'.");
	}
}

async function waitForPostgres(value: Config): Promise<void> {
	for (let attempt = 0; attempt < 30; attempt++) {
		if (await succeeds("pg_isready", ["-h", value.host, "-p", String(value.port)])) return;
		await new Promise((resolve) => {
			setTimeout(resolve, 1000);
		});
	}
	throw new Error("PostgreSQL failed to become ready after 30 seconds");
}

async function startPostgres(value: Config): Promise<void> {
	if (value.ci) return waitForPostgres(value);
	await run("docker", ["compose", "up", "-d", "--wait", "postgres"], { cwd: server });
}

async function stopPostgres(value: Config): Promise<void> {
	if (!value.ci) {
		await run("docker", ["compose", "stop", "postgres"], { cwd: server });
		await run("docker", ["compose", "rm", "-f", "postgres"], { cwd: server });
	}
}

async function migrate(value: Config, diff = false, signal?: AbortSignal): Promise<void> {
	// The single-module Liquibase run resolves the generated clients from the local repository and
	// diffs the compiled entity classes. With CI=true both are already in place (restore-server-build
	// installed the packaged reactor); a workstation builds them here.
	if (!value.ci) {
		await run(
			"./mvnw",
			["-pl", "generated-clients", "-am", "install", "-DskipTests", "--batch-mode"],
			{ cwd: server, env: value.env, signal },
		);
	}
	await run(
		"./mvnw",
		[
			"-f",
			"application/pom.xml",
			...(value.ci ? [] : ["compile"]),
			"liquibase:update",
			...(diff ? ["liquibase:diff"] : []),
			`-Dpostgres.port=${value.port}`,
		],
		{ cwd: server, env: { ...value.env, SPRING_PROFILES_ACTIVE: "local,dev" }, signal },
	);
}

async function generateErd(value: Config): Promise<void> {
	await run(
		"node",
		[
			"generate-mermaid-erd.ts",
			"--jdbc-url",
			`jdbc:postgresql://${value.host}:${value.port}/${value.env.POSTGRES_DB ?? "hephaestus"}`,
			"--username",
			value.env.POSTGRES_USER ?? "root",
			"--output",
			"../docs/contributor/erd/schema.mmd",
		],
		{
			cwd: import.meta.dirname,
			env: { ...value.env, POSTGRES_PASSWORD: value.env.POSTGRES_PASSWORD ?? "root" },
		},
	);
}

/** Applies the chain and diffs it against the JPA model; the draft XML, or undefined without drift. */
async function diffSchema(value: Config, signal?: AbortSignal): Promise<string | undefined> {
	await rm(draft, { force: true });
	await migrate(value, true, signal);
	return readFile(draft, "utf8").catch(() => undefined);
}

const changeSetPattern = /<changeSet\b[^>]*>[\s\S]*?<\/changeSet>/g;
const closingTag = "</databaseChangeLog>";

/**
 * Rewrites Liquibase's generated change sets into the repository's convention: ids numbered from
 * `<timestamp>-1`, one author, and either a new changelog file or more sets appended to the one this
 * branch already added.
 */
export function promoteDraft(draftXml: string, timestamp: number, existing?: string): string {
	const sets = draftXml.match(changeSetPattern) ?? [];
	if (sets.length === 0) throw new Error("The draft contains no change sets");
	const numbers = [...(existing ?? "").matchAll(/<changeSet id="\d+-(\d+)"/g)].map((match) =>
		Number(match[1]),
	);
	let next = Math.max(0, ...numbers);
	const renumbered = sets.map((set) =>
		set.replace(
			/<changeSet\b[^>]*>/,
			() => `<changeSet id="${timestamp}-${++next}" author="hephaestus">`,
		),
	);
	const body = renumbered.map((set) => `    ${set}\n`).join("");
	if (existing) return existing.replace(closingTag, `${body}${closingTag}`);
	return `<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
${body}${closingTag}
`;
}

/** Appends the include for `fileName` unless master.xml already lists it; the list is append-only. */
export function appendInclude(masterXml: string, fileName: string): string {
	const include = `    <include file="./changelog/${fileName}" relativeToChangelogFile="true"/>\n`;
	if (masterXml.includes(include)) return masterXml;
	return masterXml.replace(closingTag, `${include}${closingTag}`);
}

/** The changelog this branch added and main does not have, if there is exactly one. */
async function branchChangelog(): Promise<string | undefined> {
	const directory = relative(root, changelogDirectory);
	const base = (
		await output("git", ["merge-base", "HEAD", "origin/main"], { cwd: root }).catch(() =>
			output("git", ["merge-base", "HEAD", "main"], { cwd: root }),
		)
	).trim();
	const added = await output(
		"git",
		["diff", "--name-only", "--diff-filter=A", base, "HEAD", "--", directory],
		{ cwd: root },
	);
	const untracked = await output(
		"git",
		["ls-files", "--others", "--exclude-standard", "--", directory],
		{
			cwd: root,
		},
	);
	const files = `${added}\n${untracked}`
		.split("\n")
		.map((line) => line.trim())
		.filter((line) => line.endsWith("_changelog.xml"));
	const unique = [...new Set(files)];
	if (unique.length > 1)
		throw new Error(`This branch adds several changelogs: ${unique.join(", ")}`);
	return unique[0] === undefined ? undefined : join(root, unique[0]);
}

/** Writes the drift into this branch's changelog and wires it; returns the file it wrote. */
async function promote(draftXml: string): Promise<string> {
	const existing = await branchChangelog().catch(() => undefined);
	if (existing) {
		await writeFile(
			existing,
			promoteDraft(
				draftXml,
				Number(existing.match(/(\d+)_changelog\.xml$/)?.[1]),
				await readFile(existing, "utf8"),
			),
		);
		return existing;
	}
	const fileName = `${Date.now()}_changelog.xml`;
	const target = join(changelogDirectory, fileName);
	await writeFile(target, promoteDraft(draftXml, Number(fileName.split("_")[0])));
	await writeFile(master, appendInclude(await readFile(master, "utf8"), fileName));
	return target;
}

async function withDatabase(
	value: Config,
	operation: (signal: AbortSignal) => Promise<void>,
): Promise<void> {
	if (value.ci) {
		await waitForPostgres(value);
		await operation(new AbortController().signal);
		return;
	}
	const backup = `${dataDirectory}-temp-${process.pid}`;
	const controller = new AbortController();
	let interrupted: NodeJS.Signals | undefined;
	const interrupt = (signal: NodeJS.Signals): void => {
		interrupted = signal;
		controller.abort(new Error("database operation interrupted"));
	};
	const sigint = (): void => interrupt("SIGINT");
	const sigterm = (): void => interrupt("SIGTERM");
	process.once("SIGINT", sigint);
	process.once("SIGTERM", sigterm);
	try {
		await withDisposableDatabase(
			dataDirectory,
			backup,
			() => stopPostgres(value),
			async () => {
				controller.signal.throwIfAborted();
				await startPostgres(value);
				controller.signal.throwIfAborted();
				await operation(controller.signal);
				controller.signal.throwIfAborted();
			},
		);
	} finally {
		if (interrupted) process.kill(process.pid, interrupted);
	}
}

export async function withDisposableDatabase(
	data: string,
	backup: string,
	stop: () => Promise<void>,
	operation: () => Promise<void>,
): Promise<void> {
	await stop();
	const backedUp = await access(data)
		.then(() => true)
		.catch(() => false);
	if (backedUp) await rename(data, backup);
	let operationError: unknown;
	try {
		await operation();
	} catch (error) {
		operationError = error;
	}
	try {
		await stop();
	} catch (cleanupError) {
		const cleanup =
			cleanupError instanceof Error ? cleanupError : new Error("Database shutdown failed");
		if (operationError) {
			const primary =
				operationError instanceof Error ? operationError : new Error("Database operation failed");
			throw new AggregateError([primary, cleanup], "Database operation and shutdown failed", {
				cause: cleanupError,
			});
		}
		throw cleanup;
	}
	await rm(data, { recursive: true, force: true });
	if (backedUp) await rename(backup, data);
	if (operationError)
		throw operationError instanceof Error ? operationError : new Error("Database operation failed");
}

const commands = {
	"draft-changelog":
		"apply the chain, diff it against the JPA model, write the drift into this branch's changelog and refresh the ERD",
	"check-drift": "apply the chain and fail when the JPA model and the schema differ",
	"generate-erd": "apply the chain and regenerate docs/contributor/erd/schema.mmd",
} as const;

function usage(): string {
	return `Usage: node scripts/db-utils.ts <command>\n${Object.entries(commands)
		.map(([name, help]) => `  ${name.padEnd(16)} ${help}`)
		.join("\n")}`;
}

async function main(): Promise<void> {
	const command = process.argv[2];
	if (command === undefined || ["help", "-h", "--help"].includes(command)) {
		console.log(usage());
		process.exitCode = command === undefined ? 1 : 0;
		return;
	}
	if (!(command in commands)) {
		console.error(`Unknown command: ${command}\n${usage()}`);
		process.exitCode = 1;
		return;
	}
	const value = await config();
	await checkEnvironment(value);
	if (command === "generate-erd") {
		log("Regenerating the ERD...");
		await startPostgres(value);
		await migrate(value);
		await generateErd(value);
		console.log("✅ ERD regenerated.");
		return;
	}
	if (command === "check-drift") {
		log("Checking the schema against the JPA model...");
		let drift: string | undefined;
		await withDatabase(value, async (signal) => {
			drift = await diffSchema(value, signal);
		});
		if (drift) {
			console.error(`❌ The schema drifts from the JPA model:\n${drift}`);
			console.error("Run: vp run db:draft-changelog");
			process.exitCode = 1;
			return;
		}
		console.log("✅ The schema matches the JPA model.");
		return;
	}
	log("Drafting the changelog...");
	let written: string | undefined;
	await withDatabase(value, async (signal) => {
		const drift = await diffSchema(value, signal);
		if (!drift) return;
		written = await promote(drift);
		await migrate(value, false, signal);
		await generateErd(value);
	});
	if (!written) {
		console.log("✅ The schema matches the JPA model; no changelog needed.");
		return;
	}
	console.log(`✅ Wrote ${relative(root, written)} and refreshed the ERD.`);
	console.log(
		"Review for data loss and upgrade safety: docs/contributor/database-migration.mdx. Refresh the ERD if you edit the changelog.",
	);
}

if (import.meta.main) await main();
