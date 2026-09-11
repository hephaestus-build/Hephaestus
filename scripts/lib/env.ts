import { readFile } from "node:fs/promises";

export async function readEnvFile(path: string): Promise<Record<string, string>> {
	let contents: string;
	try {
		contents = await readFile(path, "utf8");
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") return {};
		throw error;
	}
	const values: Record<string, string> = {};
	for (const line of contents.split(/\r?\n/)) {
		const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line);
		if (!match) continue;
		const [, key, rawValue] = match;
		if (!key || rawValue === undefined) continue;
		const value = rawValue.trim();
		values[key] =
			(value.startsWith('"') && value.endsWith('"')) ||
			(value.startsWith("'") && value.endsWith("'"))
				? value.slice(1, -1)
				: value;
	}
	return values;
}

export function positivePort(value: string, name: string): number {
	if (!/^\d+$/.test(value)) throw new Error(`${name} must be an integer from 1 to 65535`);
	const port = Number(value);
	if (port < 1 || port > 65_535) throw new Error(`${name} must be an integer from 1 to 65535`);
	return port;
}

export function requiredEnv(environment: NodeJS.ProcessEnv, name: string): string {
	const value = environment[name];
	if (!value) throw new Error(`${name} is not configured.`);
	return value;
}

export function requiredPositiveInteger(environment: NodeJS.ProcessEnv, name: string): number {
	const value = Number(requiredEnv(environment, name));
	if (!Number.isSafeInteger(value) || value <= 0) {
		throw new Error(`${name} must be a positive whole number.`);
	}
	return value;
}

/**
 * Whether a value is a DNS hostname. Env-supplied hosts reach shell and proxy configuration, so a
 * value that is not one is rejected before it is interpolated anywhere.
 */
export function isHostname(value: string): boolean {
	return (
		value.length <= 253 &&
		value.split(".").every((label) => /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/.test(label))
	);
}
