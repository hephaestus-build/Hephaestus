import { createHash, randomBytes } from "node:crypto";
import { constants } from "node:fs";
import { access, chmod, copyFile, readdir, readFile, realpath, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet, readEnvFile } from "./lib/env.ts";
import { output } from "./lib/process.ts";

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
export const PORTS = [
	{ name: "POSTGRES_PORT", port: 5432, label: "PostgreSQL" },
	{ name: "SERVER_PORT", port: 8080, label: "Application server" },
	{ name: "SANDBOX_API_PORT", port: 8081, label: "Sandbox gateway" },
	{ name: "MAILPIT_SMTP_PORT", port: 1025, label: "Mailpit SMTP" },
	{ name: "MAILPIT_UI_PORT", port: 8025, label: "Mailpit UI" },
	{ name: "WEBAPP_PORT", port: 4200, label: "Webapp (Vite)" },
	{ name: "MANAGEMENT_PORT", port: 9090, label: "Management" },
] as const;
const PORT_SETS = 100;
export const MANAGEMENT_OFFSET = 1010;

/** The default of a host port, the one home that `check-ports.ts` and `dev-kubernetes.ts` read too. */
export function defaultPort(name: (typeof PORTS)[number]["name"]): number {
	return PORTS.find((entry) => entry.name === name)?.port ?? 0;
}

/** Every host port that a settings file uses, with the defaults for what it does not set. */
export function portsOf(values: Record<string, string | undefined>): Set<number> {
	const value = (name: string, fallback: number) => {
		const configured = values[name];
		return isSet(configured) ? Number(configured) : fallback;
	};
	const serverPort = value("SERVER_PORT", defaultPort("SERVER_PORT"));
	return new Set(
		PORTS.map(({ name, port }) =>
			value(name, name === "MANAGEMENT_PORT" ? serverPort + MANAGEMENT_OFFSET : port),
		),
	);
}

/** The host ports that a settings file sets, and the management port that follows a server port it sets. */
export function portsSetIn(values: Record<string, string | undefined>): number[] {
	const ports = PORTS.flatMap(({ name }) => {
		const configured = values[name];
		return isSet(configured) ? [Number(configured)] : [];
	});
	const configuredServer = values.SERVER_PORT;
	if (isSet(configuredServer) && !isSet(values.MANAGEMENT_PORT)) {
		ports.push(Number(configuredServer) + MANAGEMENT_OFFSET);
	}
	return ports;
}

/** The settings files of the other worktrees of this clone. A worktree that never ran a dev command has none. */
async function otherWorktreeSettings(checkout: string): Promise<Record<string, string>[]> {
	let listing: string;
	try {
		listing = await output("git", ["worktree", "list", "--porcelain", "-z"], { cwd: checkout });
	} catch {
		// A checkout outside Git, such as a source archive, has no other worktrees.
		return [];
	}
	const self = await realpath(checkout);
	const others = listing
		.split("\0")
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
 * Gives this checkout its own host ports, once: each port the file does not set comes from the first port set that
 * no other worktree of the clone uses. A port the developer set stays, and the management port follows a server
 * port they set. Returns the ports it wrote.
 *
 * A worktree whose file sets no ports runs on the defaults. A file just created, or one that sets some ports, avoids
 * those worktrees, because one may be running. An older file without ports was such a worktree itself, so only
 * ports that another file set move it: the first older worktree keeps the defaults.
 */
export async function ensurePorts({
	envFile,
	others,
	created,
	environment = process.env,
}: {
	envFile: string;
	others: Record<string, string | undefined>[];
	created: boolean;
	environment?: NodeJS.ProcessEnv;
}): Promise<Record<string, number>> {
	const values = { ...(await readEnvFile(envFile)), ...environment };
	const unset = PORTS.filter(({ name }) => !isSet(values[name]));
	if (unset.length === 0) {
		return {};
	}
	// A file that sets no ports belongs to a worktree that runs on the defaults. An older file of that kind
	// keeps them unless another file set them; every other file avoids the defaults of such worktrees too.
	const ownsTheDefaults = !created && unset.length === PORTS.length;
	const taken = new Set(
		others.flatMap((other) => (ownsTheDefaults ? portsSetIn(other) : [...portsOf(other)])),
	);
	const developerServer = isSet(values.SERVER_PORT) ? Number(values.SERVER_PORT) : undefined;
	for (let set = 0; set < PORT_SETS; set += 1) {
		const written = Object.fromEntries(
			unset.map(({ name, port }) => [
				name,
				name === "MANAGEMENT_PORT" && developerServer !== undefined
					? developerServer + MANAGEMENT_OFFSET
					: port + 10 * set,
			]),
		);
		if (Object.values(written).some((port) => taken.has(port))) {
			continue;
		}
		for (const [name, port] of Object.entries(written)) {
			await setEnvLine(envFile, name, String(port));
		}
		return written;
	}
	throw new Error(
		`Every one of the ${PORT_SETS} port sets is in use by another worktree. Set the ports in server/.env.`,
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
	const created = await ensureEnvFile({
		envFile: paths.envFile,
		template: path.join(server, ".env.example"),
	});
	if (created) {
		console.log(
			"ℹ️  Created server/.env from server/.env.example. Edit it; never copy the example over it.",
		);
	}
	const checkout = path.dirname(server);
	await ensureComposeProject({ ...paths, checkout });
	const ports = await ensurePorts({
		envFile: paths.envFile,
		others: await otherWorktreeSettings(checkout),
		created,
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
