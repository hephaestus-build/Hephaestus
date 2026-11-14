import { execFile } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { constants } from "node:fs";
import { access, chmod, copyFile, readdir, readFile, realpath, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

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
 * The host ports of a checkout and their defaults. Port set `n` adds `10 * n` to each default, so the management
 * port stays `SERVER_PORT + 1010`, the value `dev-kubernetes.ts` derives when it is unset. Within 100 sets, no port
 * of one set equals a port of another: the ranges that overlap differ in their last digit.
 */
const PORTS = [
	{ name: "POSTGRES_PORT", port: 5432 },
	{ name: "SERVER_PORT", port: 8080 },
	{ name: "SANDBOX_API_PORT", port: 8081 },
	{ name: "MAILPIT_SMTP_PORT", port: 1025 },
	{ name: "MAILPIT_UI_PORT", port: 8025 },
	{ name: "WEBAPP_PORT", port: 4200 },
	{ name: "MANAGEMENT_PORT", port: 9090 },
] as const;
const PORT_SETS = 100;
const MANAGEMENT_OFFSET = 1010;

/** Every host port that a settings file uses, with the defaults for what it does not set. */
export function portsOf(values: Record<string, string | undefined>): Set<number> {
	const value = (name: string, fallback: number) => {
		const configured = values[name];
		return isSet(configured) ? Number(configured) : fallback;
	};
	const server = value("SERVER_PORT", 8080);
	return new Set(
		PORTS.map(({ name, port }) =>
			value(name, name === "MANAGEMENT_PORT" ? server + MANAGEMENT_OFFSET : port),
		),
	);
}

/** The settings files of the other worktrees of this clone. A worktree that never ran a dev command has none. */
async function otherWorktreeSettings(checkout: string): Promise<Record<string, string>[]> {
	let listing: string;
	try {
		({ stdout: listing } = await promisify(execFile)("git", ["worktree", "list", "--porcelain"], {
			cwd: checkout,
		}));
	} catch {
		// A checkout outside Git, such as a source archive, has no other worktrees.
		return [];
	}
	const self = await realpath(checkout);
	const others = listing
		.split("\n")
		.filter((line) => line.startsWith("worktree "))
		.map((line) => line.slice("worktree ".length));
	const settings: Record<string, string>[] = [];
	for (const other of others) {
		const envFile = path.join(other, "server", ".env");
		const resolved = await realpath(other).catch(() => other);
		if (
			resolved !== self &&
			(await access(envFile).then(
				() => true,
				() => false,
			))
		) {
			settings.push(await readEnvFile(envFile));
		}
	}
	return settings;
}

/**
 * Gives this checkout its own host ports, once: the first port set that no other worktree of the clone uses.
 * A file that sets `SERVER_PORT` keeps its ports and only gets the management port that follows from it.
 * Returns the ports it wrote.
 */
export async function ensurePorts({
	envFile,
	others,
	environment = process.env,
}: {
	envFile: string;
	others: Record<string, string | undefined>[];
	environment?: NodeJS.ProcessEnv;
}): Promise<Record<string, number>> {
	const values = { ...(await readEnvFile(envFile)), ...environment };
	if (isSet(values.SERVER_PORT)) {
		if (isSet(values.MANAGEMENT_PORT)) {
			return {};
		}
		// The value `dev-kubernetes.ts` derives, written so that every way of starting the server uses it.
		const management = Number(values.SERVER_PORT) + MANAGEMENT_OFFSET;
		await setEnvLine(envFile, "MANAGEMENT_PORT", String(management));
		return { MANAGEMENT_PORT: management };
	}
	// Only the ports this command writes must be free. A port the developer set is their choice.
	const taken = new Set(others.flatMap((other) => [...portsOf(other)]));
	const unset = PORTS.filter(({ name }) => !isSet(values[name]));
	for (let set = 0; set < PORT_SETS; set += 1) {
		const written = Object.fromEntries(unset.map(({ name, port }) => [name, port + 10 * set]));
		if (Object.values(written).some((port) => taken.has(port))) {
			continue;
		}
		for (const [name, port] of Object.entries(written)) {
			await setEnvLine(envFile, name, String(port));
		}
		return written;
	}
	throw new Error(
		`Every one of the ${PORT_SETS} port sets is in use by another worktree. Set SERVER_PORT and the other ports in server/.env.`,
	);
}

/**
 * The one writer of `server/.env`: it creates the file from `server/.env.example`, then adds the Compose
 * project, the host ports and the generated values that are missing, and says what it wrote.
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
	const checkout = path.dirname(server);
	await ensureComposeProject({ ...paths, checkout });
	const ports = await ensurePorts({
		envFile: paths.envFile,
		others: await otherWorktreeSettings(checkout),
	});
	if (Object.keys(ports).length > 0) {
		console.log(
			`ℹ️  Gave this worktree its own ports in server/.env: ${Object.entries(ports)
				.map(([name, port]) => `${name}=${port}`)
				.join(", ")}.`,
		);
	}
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
