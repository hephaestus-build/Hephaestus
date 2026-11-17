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
