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
 * The first JRE build that fixes CVE-2026-114938, a reported pre-auth flaw in the JVM's AVX-512 Base64
 * decoder. A pinned JRE below it must run with the decoder turned off.
 */
export const MITIGATED_BELOW = [25, 0, 5, 1] as const;
export const BASE64_MITIGATION = "-XX:-UseBASE64Intrinsics";

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

/** Whether every option string names the Base64 mitigation as a whole option. */
export function hasBase64Mitigation(...options: readonly string[]): boolean {
	return options.every((value) => value.split(/\s+/u).includes(BASE64_MITIGATION));
}
