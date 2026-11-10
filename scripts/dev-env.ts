import { createHash, randomBytes } from "node:crypto";
import { readdir, readFile, realpath, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet, readEnvFile } from "./lib/env.ts";

const server = path.join(import.meta.dirname, "../server");

/**
 * What `ensureDatabasePassword` did to `server/.env`:
 * - `present`: the file or the environment already names a password; nothing changed.
 * - `generated`: a random password was written, for a database that does not exist yet.
 * - `legacy`: a database already existed, so it holds the password that the old Compose file gave it.
 *   PostgreSQL reads `POSTGRES_PASSWORD` only when it creates a database. A new password would lock
 *   the developer out of their data, so the old one is kept until `dev:reset` removes the data.
 */
export type DatabasePasswordOutcome = "present" | "generated" | "legacy";

const legacyPassword = "root";
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
 * A kept legacy password with no data left is replaced: that is how `dev:reset` rotates it.
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
	const legacy = await hasData(dataDirectory);
	const values = await readEnvFile(envFile);
	const configured = values.POSTGRES_PASSWORD;
	if (
		isSet(environment.POSTGRES_PASSWORD) ||
		(isSet(configured) && (legacy || configured !== legacyPassword))
	) {
		return "present";
	}
	const password = legacy ? legacyPassword : randomBytes(24).toString("hex");
	await setEnvLine(envFile, assignment, `POSTGRES_PASSWORD=${password}`);
	return legacy ? "legacy" : "generated";
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
 * containers of another. A checkout whose database already exists keeps `server`: its running
 * containers carry that name, and a new name would start a second stack on the same ports.
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
	const configured =
		environment.COMPOSE_PROJECT_NAME ?? (await readEnvFile(envFile)).COMPOSE_PROJECT_NAME;
	if (isSet(configured)) {
		return configured;
	}
	let name = "server";
	if (!(await hasData(dataDirectory))) {
		const folder = path
			.basename(checkout)
			.toLowerCase()
			.replaceAll(/[^a-z0-9_-]+/gu, "-");
		const id = createHash("sha256")
			.update(await realpath(checkout))
			.digest("hex")
			.slice(0, 8);
		name = `hephaestus-${folder}-${id}`;
	}
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
	} else if (outcome === "legacy") {
		console.warn(
			"⚠️  server/postgres-data already holds a database, created with the old fixed password. " +
				"server/.env now names it as POSTGRES_PASSWORD so that the database keeps working. " +
				"Run `vp run dev:reset` to replace it with a generated password. That deletes the local data.",
		);
	}
}

if (import.meta.main) {
	await ensureLocalEnvironment();
}
