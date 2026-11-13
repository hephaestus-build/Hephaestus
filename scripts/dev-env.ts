import { createHash, randomBytes } from "node:crypto";
import { readdir, readFile, realpath, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet, readEnvFile } from "./lib/env.ts";

const server = path.join(import.meta.dirname, "../server");

/**
 * What `ensureDatabasePassword` did to `server/.env`:
 * - `present`: the file or the environment already names a password; nothing changed.
 * - `generated`: a random password was written, for a database that does not exist yet.
 */
export type DatabasePasswordOutcome = "present" | "generated";

const assignment = /^POSTGRES_PASSWORD=.*$/mu;

async function hasData(dataDirectory: string): Promise<boolean> {
	try {
		const entries = await readdir(dataDirectory);
		return entries.length > 0;
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") {
			return false;
		}
		throw error;
	}
}

/**
 * Gives this checkout's local PostgreSQL a password, once. `server/compose.yaml` creates the database
 * with it, and Spring Boot and every database script read it from the same `server/.env`.
 */
export async function ensureDatabasePassword({
	envFile,
	dataDirectory,
	environment = process.env,
}: {
	envFile: string;
	dataDirectory: string;
	environment?: NodeJS.ProcessEnv;
}): Promise<DatabasePasswordOutcome> {
	const values = await readEnvFile(envFile);
	const configured = values.POSTGRES_PASSWORD;
	if (isSet(environment.POSTGRES_PASSWORD) || isSet(configured)) {
		return "present";
	}
	if (await hasData(dataDirectory)) {
		throw new Error(
			"The local database has data but no POSTGRES_PASSWORD. Set its password in server/.env before running local database commands.",
		);
	}
	const password = randomBytes(24).toString("hex");
	await setEnvLine(envFile, assignment, `POSTGRES_PASSWORD=${password}`);
	return "generated";
}

/** Replaces the line that `pattern` matches, or appends one. The file holds secrets, so only its owner reads it. */
async function setEnvLine(envFile: string, pattern: RegExp, line: string): Promise<void> {
	const current = await readFile(envFile, "utf8").catch(() => "");
	const next = pattern.test(current)
		? current.replace(pattern, () => line)
		: `${current}${current === "" || current.endsWith("\n") ? "" : "\n"}${line}\n`;
	await writeFile(envFile, next, { mode: 0o600 });
}

/**
 * Gives this checkout's Compose stack a project name of its own, once. Without one, Compose names
 * every checkout's project after its folder, `server`, and a command in one checkout acts on the
 * containers of another. Existing data requires its configured project name before any command runs.
 */
export async function ensureComposeProject({
	envFile,
	dataDirectory,
	checkout,
	environment = process.env,
}: {
	envFile: string;
	dataDirectory: string;
	checkout: string;
	environment?: NodeJS.ProcessEnv;
}): Promise<string> {
	const values = await readEnvFile(envFile);
	const configured = environment.COMPOSE_PROJECT_NAME ?? values.COMPOSE_PROJECT_NAME;
	if (isSet(configured)) {
		return configured;
	}
	if (await hasData(dataDirectory)) {
		throw new Error(
			"The local database has data but no COMPOSE_PROJECT_NAME. Set its project name in server/.env before running local database commands.",
		);
	}
	const folder = path
		.basename(checkout)
		.toLowerCase()
		.replaceAll(/[^a-z0-9_-]+/gu, "-");
	const id = createHash("sha256")
		.update(await realpath(checkout))
		.digest("hex")
		.slice(0, 8);
	const name = `hephaestus-${folder}-${id}`;
	await setEnvLine(envFile, /^COMPOSE_PROJECT_NAME=.*$/mu, `COMPOSE_PROJECT_NAME=${name}`);
	return name;
}

/**
 * Ensures the Compose project and the password for this checkout, and tells the developer what
 * happened when it changed the password.
 */
export async function ensureLocalEnvironment(): Promise<void> {
	const paths = {
		envFile: path.join(server, ".env"),
		dataDirectory: path.join(server, "postgres-data"),
	};
	await ensureComposeProject({ ...paths, checkout: path.dirname(server) });
	const outcome = await ensureDatabasePassword(paths);
	if (outcome === "generated") {
		console.log("ℹ️  Generated POSTGRES_PASSWORD in server/.env for this checkout's database.");
	}
}

if (import.meta.main) {
	await ensureLocalEnvironment();
}
