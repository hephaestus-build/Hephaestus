import { createHash, randomBytes } from "node:crypto";
import { constants } from "node:fs";
import { chmod, copyFile, readdir, readFile, realpath, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet, readEnvFile } from "./lib/env.ts";

const server = path.join(import.meta.dirname, "../server");

/**
 * The values this script generates, with their random bytes, written as hex. PostgreSQL reads its password only
 * when it creates a database, and the server reads stored secrets only with the keys that encrypted them, so
 * those three are generated for a new database only. The two keys take 32 UTF-8 bytes, as
 * docs/admin/secret-custody.mdx generates them.
 */
const GENERATED = [
	{ name: "POSTGRES_PASSWORD", bytes: 24, newDatabaseOnly: true },
	{ name: "HEPHAESTUS_SECURITY_ENCRYPTION_KEY", bytes: 16, newDatabaseOnly: true },
	{ name: "HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY", bytes: 16, newDatabaseOnly: true },
	{ name: "WEBHOOK_SECRET", bytes: 32, newDatabaseOnly: false },
] as const;
type Generated = (typeof GENERATED)[number]["name"];

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
 * Creates `server/.env` from the template, once. Every later step only adds a value that is missing, so a
 * value the developer set always stays. Returns whether it created the file.
 */
export async function ensureEnvFile({
	envFile,
	template,
}: {
	envFile: string;
	template: string;
}): Promise<boolean> {
	try {
		await copyFile(template, envFile, constants.COPYFILE_EXCL);
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "EEXIST") {
			return false;
		}
		throw error;
	}
	// The file holds secrets, so only its owner reads it.
	await chmod(envFile, 0o600);
	return true;
}

/**
 * Generates each value of `GENERATED` that neither the file nor the environment sets, and returns their names.
 * Existing data refuses a missing database password: a new one would lock the developer out of the data.
 */
export async function ensureGeneratedValues({
	envFile,
	dataDirectory,
	environment = process.env,
}: {
	envFile: string;
	dataDirectory: string;
	environment?: NodeJS.ProcessEnv;
}): Promise<Generated[]> {
	const values = await readEnvFile(envFile);
	const missing = GENERATED.filter(({ name }) => !isSet(environment[name]) && !isSet(values[name]));
	const existingData = await hasData(dataDirectory);
	if (existingData && missing.some(({ name }) => name === "POSTGRES_PASSWORD")) {
		throw new Error(
			"The local database has data but no POSTGRES_PASSWORD. Set its password in server/.env, or run `vp run dev:reset` to start again with a new database.",
		);
	}
	const written = missing.filter(({ newDatabaseOnly }) => !(existingData && newDatabaseOnly));
	for (const { name, bytes } of written) {
		await setEnvLine(envFile, name, randomBytes(bytes).toString("hex"));
	}
	return written.map(({ name }) => name);
}

/**
 * Sets `name`, which the file leaves blank or unset. Readers take the last assignment of a key, so the last one is
 * replaced, or a line is appended. The file holds secrets, so only its owner reads it.
 */
async function setEnvLine(envFile: string, name: string, value: string): Promise<void> {
	const current = await readFile(envFile, "utf8").catch(() => "");
	const last = [...current.matchAll(new RegExp(`^${name}=.*$`, "gmu"))].at(-1);
	const line = `${name}=${value}`;
	const next =
		last === undefined
			? `${current}${current === "" || current.endsWith("\n") ? "" : "\n"}${line}\n`
			: `${current.slice(0, last.index)}${line}${current.slice(last.index + last[0].length)}`;
	await writeFile(envFile, next);
	await chmod(envFile, 0o600);
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
	await setEnvLine(envFile, "COMPOSE_PROJECT_NAME", name);
	return name;
}

/**
 * The one writer of `server/.env`: it creates the file from `server/.env.example`, then adds the Compose
 * project and the generated values that are missing, and says what it wrote.
 */
export async function ensureLocalEnvironment(): Promise<void> {
	const paths = {
		envFile: path.join(server, ".env"),
		dataDirectory: path.join(server, "postgres-data"),
	};
	if (
		await ensureEnvFile({ envFile: paths.envFile, template: path.join(server, ".env.example") })
	) {
		console.log(
			"ℹ️  Created server/.env from server/.env.example. Edit it; never copy the example over it.",
		);
	}
	await ensureComposeProject({ ...paths, checkout: path.dirname(server) });
	const written = await ensureGeneratedValues(paths);
	if (written.length > 0) {
		console.log(`ℹ️  Generated ${written.join(", ")} in server/.env for this checkout.`);
	}
	const values = { ...(await readEnvFile(paths.envFile)), ...process.env };
	const keys = GENERATED.filter(
		({ newDatabaseOnly, name }) => newDatabaseOnly && !isSet(values[name]),
	);
	if (keys.length > 0) {
		console.warn(
			`⚠️  The local database has data but server/.env has no ${keys.map(({ name }) => name).join(" or ")}. Set the keys it was created with, or run \`vp run dev:reset\`.`,
		);
	}
}

if (import.meta.main) {
	await ensureLocalEnvironment();
}
