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

/** The pin of the image's JRE: its files, and the JVM buildpack that installs them. */
export const JRE_PIN = new URL("../../.github/ci-tools/buildpack-jre.json", import.meta.url);

const JVM_BUILDPACK = /^docker:\/\/[a-z0-9./-]+@sha256:[a-f0-9]{64}$/u;

/**
 * The descriptor that a build uses: `project.toml` with the pin's JVM buildpack named before
 * `paketo-buildpacks/java`, which then skips its own Liberica (paketo.io/docs/howto/java § Use an Alternative JVM).
 * A pin without `buildpack` uses the builder's Liberica, and the descriptor stays as it is. So switching the JVM
 * vendor changes the pin file alone.
 */
export function withJvmBuildpack(descriptor: string, buildpack: string | undefined): string {
	if (buildpack === undefined) {
		return descriptor;
	}
	if (!JVM_BUILDPACK.test(buildpack)) {
		throw new Error(
			`The JVM buildpack must be a docker:// image pinned by digest, not ${buildpack}`,
		);
	}
	const java = /^\[\[io\.buildpacks\.group\]\]\nid = "paketo-buildpacks\/java"$/mu;
	if (!java.test(descriptor)) {
		throw new Error(
			"The descriptor names no paketo-buildpacks/java group to put the JVM buildpack before",
		);
	}
	return descriptor.replace(
		java,
		`[[io.buildpacks.group]]\nuri = "${buildpack}"\n\n[[io.buildpacks.group]]\nid = "paketo-buildpacks/java"`,
	);
}

/** The JVM buildpack that the JRE pin names, or `undefined` for the builder's Liberica. */
export function jvmBuildpack(pin: unknown): string | undefined {
	if (typeof pin !== "object" || pin === null || !("buildpack" in pin)) {
		return undefined;
	}
	if (typeof pin.buildpack !== "string") {
		throw new Error("The JRE pin's buildpack must be a string");
	}
	return pin.buildpack;
}

/** The descriptor of the server image as its build uses it. */
export function serverDescriptor(descriptor: string | URL = DESCRIPTOR): string {
	return withJvmBuildpack(
		readFileSync(descriptor, "utf8"),
		jvmBuildpack(JSON.parse(readFileSync(JRE_PIN, "utf8"))),
	);
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

/** Whether a JRE version such as `25.0.5.0.3_5` (Liberica) or `25.0.5_8` (Temurin) is below `fixed`, part by part. */
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
