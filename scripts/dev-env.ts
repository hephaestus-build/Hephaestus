import { randomBytes } from "node:crypto";
import { readdir, readFile, writeFile } from "node:fs/promises";
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
	const line = `POSTGRES_PASSWORD=${password}`;
	const current = await readFile(envFile, "utf8").catch(() => "");
	const next = assignment.test(current)
		? current.replace(assignment, () => line)
		: `${current}${current === "" || current.endsWith("\n") ? "" : "\n"}${line}\n`;
	await writeFile(envFile, next, { mode: 0o600 });
	return legacy ? "legacy" : "generated";
}

/** Ensures the password for this checkout and tells the developer what happened when it changed anything. */
export async function ensureLocalDatabasePassword(): Promise<void> {
	const outcome = await ensureDatabasePassword({
		envFile: path.join(server, ".env"),
		dataDirectory: path.join(server, "postgres-data"),
	});
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
	await ensureLocalDatabasePassword();
}
