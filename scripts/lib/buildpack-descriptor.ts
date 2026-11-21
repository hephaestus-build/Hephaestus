import { readFileSync } from "node:fs";

export const DESCRIPTOR = new URL("../../server/application/project.toml", import.meta.url);

/**
 * The build environment of a buildpack descriptor: each `[[io.buildpacks.build.env]]` table's name and
 * value. The descriptor writes each table as a `name = "…"` line and a `value = "…"` line.
 */
export function buildEnvironment(descriptor: string): ReadonlyMap<string, string> {
	const environment = new Map<string, string>();
	for (const table of descriptor.split(/^\[\[io\.buildpacks\.build\.env\]\]$/mu).slice(1)) {
		const name = /^name\s*=\s*"(?<name>[^"]*)"$/mu.exec(table)?.groups?.name;
		const value = /^value\s*=\s*"(?<value>[^"]*)"$/mu.exec(table)?.groups?.value;
		if (name === undefined || value === undefined) {
			throw new Error(`A build env table of the descriptor has no name or value:${table}`);
		}
		environment.set(name, value);
	}
	return environment;
}

/** The build environment of the server image's descriptor. */
export function serverBuildEnvironment(): ReadonlyMap<string, string> {
	return buildEnvironment(readFileSync(DESCRIPTOR, "utf8"));
}

/** The modules that a `JAVA_TOOL_OPTIONS` value adds, in their order. */
export function addedModules(options: string): readonly string[] {
	return [...options.matchAll(/(?:^|\s)--add-modules[=\s](?<modules>\S+)/gu)].flatMap(
		(match) => match.groups?.modules?.split(",") ?? [],
	);
}

/**
 * The first JRE build that fixes the OpenJDK advisory of 2026-11-17 (CVE-2026-114938 to CVE-2026-114947).
 * gate:toolchain refuses a pin below it, without waiting for an image scan to match the runtime.
 */
export const JRE_FLOOR = [25, 0, 5, 1] as const;

/** Whether a Liberica version such as `25.0.5.0.3_5` is below `fixed`, compared part by part. */
export function versionBelow(version: string, fixed: readonly number[]): boolean {
	const parts = (version.split("_")[0] ?? "").split(".").map(Number);
	for (let index = 0; index < Math.max(parts.length, fixed.length); index += 1) {
		const left = parts[index] ?? 0;
		const right = fixed[index] ?? 0;
		if (left !== right) {
			return left < right;
		}
	}
	return false;
}

/** The object-header layout that a `JAVA_TOOL_OPTIONS` value selects, or `undefined` for the JVM's default. */
export function objectHeaders(options: string): "compact" | "legacy" | undefined {
	const settings = [
		...options.matchAll(/(?:^|\s)-XX:(?<sign>[+-])UseCompactObjectHeaders(?=\s|$)/gu),
	];
	const last = settings.at(-1)?.groups?.sign;
	if (last === undefined) {
		return undefined;
	}
	return last === "+" ? "compact" : "legacy";
}
