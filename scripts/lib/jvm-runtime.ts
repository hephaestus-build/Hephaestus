/**
 * Adds the vulnerabilities of an image's Java runtime to its Trivy report.
 *
 * Trivy reads JVM archives, but not a JRE that a tarball installed: it reports no package for
 * `/layers/paketo-buildpacks_bellsoft-liberica/jre`, so no gate saw a JVM CVE. Syft's
 * `java-jvm-cataloger` reads the runtime's `release` file and records it with an `oracle:openjdk` CPE, and
 * Grype matches that CPE. Grype's own matcher misses a build of a fourth-part fix (`rangeMatches`), so the
 * same database's ranges are also compared here. This script appends the matches to the Trivy report as
 * one more result, so `check-release-vulnerabilities.ts` judges them under the same policy, and release
 * verification re-derives them from the same archived report.
 *
 * The server image must contain a JVM. For an image built from this checkout (`pinned`), it must also be
 * the JRE that `.github/ci-tools/buildpack-liberica-jre.json` pins.
 */
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { asArray, asRecord, asString, isRecord, parseJson, readJsonFile } from "./json.ts";
import { output, run } from "./process.ts";
import { syftImageArguments } from "./syft.ts";

/** The Trivy result type of the appended findings. */
export const JVM_RESULT_TYPE = "jvm";

/** Images that are Java services, and the pin that names the JRE each is built with. */
export const JAVA_IMAGES: Readonly<Record<string, string>> = {
	server: ".github/ci-tools/buildpack-liberica-jre.json",
};

/** A Grype database older than this fails the scan instead of judging against stale advisories. */
const GRYPE_DATABASE_MAX_AGE = "48h";

export interface JvmPackage {
	readonly cpes: readonly string[];
	readonly location: string;
	readonly name: string;
	readonly version: string;
}

/** The JVM installations that Syft's `java-jvm-cataloger` found. */
export function jvmPackages(syftInput: unknown): JvmPackage[] {
	const syft = asRecord(syftInput, "Syft SBOM");
	return asArray(syft.artifacts, "Syft artifacts")
		.map((value, index) => asRecord(value, `Syft artifacts[${index}]`))
		.filter((artifact) => artifact.foundBy === "java-jvm-cataloger")
		.map((artifact) => {
			const [location] = asArray(artifact.locations, "JVM locations");
			return {
				cpes: asArray(artifact.cpes ?? [], "JVM CPEs").map((cpe) =>
					asString(asRecord(cpe, "JVM CPE").cpe, "JVM CPE"),
				),
				location: asString(asRecord(location, "JVM location").path, "JVM location path"),
				name: asString(artifact.name, "JVM name"),
				version: asString(artifact.version, "JVM version"),
			};
		});
}

/**
 * Whether a JVM's `JAVA_RUNTIME_VERSION`, such as `25.0.5.0.3+5-LTS`, is the build that a pin such as
 * `25.0.5.0.3_5` names. An OCI tag cannot carry `+`, so the pin writes `_`.
 */
export function isPinnedBuild(runtimeVersion: string, pinnedVersion: string): boolean {
	const pinned = pinnedVersion.replace("_", "+");
	return runtimeVersion === pinned || runtimeVersion.startsWith(`${pinned}-`);
}

/**
 * Refuses an inventory that lost the runtime. A Java image without a JVM, or a JVM without a CPE, is an
 * inventory that a matcher cannot use, not a clean one.
 */
export function assertRuntimeInventory(
	image: string,
	packages: readonly JvmPackage[],
	pinnedVersion?: string,
): void {
	for (const jvm of packages) {
		if (jvm.cpes.length === 0) {
			throw new Error(`${image}: Syft recorded the JVM at ${jvm.location} without a CPE`);
		}
	}
	if (!Object.hasOwn(JAVA_IMAGES, image)) {
		return;
	}
	if (packages.length === 0) {
		throw new Error(`${image}: Syft found no JVM, so its scan cannot cover the Java runtime`);
	}
	if (
		pinnedVersion !== undefined &&
		!packages.some((jvm) => isPinnedBuild(jvm.version, pinnedVersion))
	) {
		throw new Error(
			`${image}: the image runs JVM ${packages.map((jvm) => jvm.version).join(", ")}, not the pinned JRE ${pinnedVersion}`,
		);
	}
}

const SEVERITIES: Readonly<Record<string, string>> = {
	Critical: "CRITICAL",
	High: "HIGH",
	Medium: "MEDIUM",
	Low: "LOW",
	Negligible: "LOW",
	Unknown: "UNKNOWN",
};

/** Grype's matches as Trivy vulnerabilities, so the evaluator reads them as any other finding. */
export function jvmResult(grypeInput: unknown, jvm: JvmPackage): Record<string, unknown> {
	const grype = asRecord(grypeInput, "Grype report");
	const vulnerabilities = new Map<string, Record<string, unknown>>();
	for (const [index, value] of asArray(grype.matches, "Grype matches").entries()) {
		const match = asRecord(value, `Grype matches[${index}]`);
		const vulnerability = asRecord(match.vulnerability, "Grype vulnerability");
		const artifact = asRecord(match.artifact, "Grype artifact");
		const id = asString(vulnerability.id, "Grype vulnerability id");
		const severity = SEVERITIES[asString(vulnerability.severity, "Grype severity")];
		if (severity === undefined) {
			throw new Error(`Grype reported an unknown severity for ${id}`);
		}
		const fix = asRecord(vulnerability.fix, "Grype fix");
		const fixed =
			fix.state === "fixed"
				? asArray(fix.versions, "Grype fix versions").map((version) =>
						asString(version, "Grype fix version"),
					)
				: [];
		// Grype lists the fixed build of every Java line; the installed line's is the actionable one.
		const line = asString(artifact.version, "Grype artifact version").split(".")[0];
		const sameLine = fixed.filter((version) => version.split(".")[0] === line);
		vulnerabilities.set(id, {
			VulnerabilityID: id,
			PkgName: asString(artifact.name, "Grype artifact name"),
			InstalledVersion: asString(artifact.version, "Grype artifact version"),
			...(fixed.length > 0
				? { FixedVersion: (sameLine.length > 0 ? sameLine : fixed).join(", ") }
				: {}),
			Severity: severity,
		});
	}
	return {
		Target: jvm.location.replace(/^\//u, ""),
		Class: "lang-pkgs",
		Type: JVM_RESULT_TYPE,
		Vulnerabilities: [...vulnerabilities.values()].toSorted((left, right) =>
			String(left.VulnerabilityID).localeCompare(String(right.VulnerabilityID)),
		),
	};
}

/** The `vendor:product` of a CPE 2.3 name, such as `oracle:openjdk`. */
function cpeProduct(cpe: string): string {
	return cpe.split(":").slice(3, 5).join(":");
}

/** A JVM version's numeric parts: `25.0.5.0.3+5-LTS` is 25.0.5.0.3, and `1.8.0_512` is 1.8.0.512. */
function versionParts(version: string): number[] {
	const parts = (version.split(/[+-]/u)[0] ?? "").match(/\d+/gu);
	if (parts === null) {
		throw new Error(`the JVM version ${version} has no number`);
	}
	return parts.map(Number);
}

function compareParts(left: readonly number[], right: readonly number[]): number {
	for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
		const difference = (left[index] ?? 0) - (right[index] ?? 0);
		if (difference !== 0) {
			return difference;
		}
	}
	return 0;
}

const COMPARISONS: Readonly<Record<string, (order: number) => boolean>> = {
	"<": (order) => order < 0,
	"<=": (order) => order <= 0,
	">": (order) => order > 0,
	">=": (order) => order >= 0,
	"=": (order) => order === 0,
};

/**
 * Whether a version satisfies a Grype constraint such as `>= 22, < 25.0.5.1 || >= 26, < 27.0.1.1`, with
 * every part compared. A bound such as `8-update72` compares on its numbers.
 */
export function satisfiesConstraint(installed: readonly number[], constraint: string): boolean {
	return constraint.split("||").some((group) =>
		group.split(",").every((term) => {
			const match = /^(?<operator><=|>=|<|>|=)?\s*(?<bound>\S+)$/u.exec(term.trim());
			if (match?.groups?.bound === undefined) {
				throw new Error(`Grype constraint ${constraint} has a term that is not a comparison`);
			}
			const order = compareParts(installed, versionParts(match.groups.bound));
			return COMPARISONS[match.groups.operator ?? "="]?.(order) ?? false;
		}),
	);
}

const capitalized = (value: string) => `${value.charAt(0).toUpperCase()}${value.slice(1)}`;

/**
 * The vulnerabilities whose database ranges contain the JVM, as Grype matches. Grype compares `jvm`
 * versions on three parts, so it reads `< 25.0.5.1` as `< 25.0.5` and matches no build of 25.0.5, though
 * 25.0.5.0.3 is affected: a vendor's out-of-band fix is a fourth-part release. This reads the ranges of
 * `grype db search` for the JVM's CPE products and compares every part. Its matches join Grype's own.
 */
export function rangeMatches(searchInput: unknown, jvm: JvmPackage): Record<string, unknown>[] {
	const products = new Set(jvm.cpes.map(cpeProduct));
	const installed = versionParts(jvm.version);
	const matches: Record<string, unknown>[] = [];
	for (const [index, value] of asArray(searchInput, "Grype database search").entries()) {
		const row = asRecord(value, `Grype database search[${index}]`);
		const vulnerability = asRecord(row.vulnerability, "Grype database vulnerability");
		const id = asString(vulnerability.id, "Grype database vulnerability id");
		for (const packageValue of asArray(row.packages, `${id} packages`)) {
			const affected = asRecord(packageValue, `${id} package`);
			if (!products.has(cpeProduct(asString(affected.cpe, `${id} package CPE`)))) {
				continue;
			}
			const detail = asRecord(affected.detail, `${id} package detail`);
			for (const rangeValue of asArray(detail.ranges ?? [], `${id} ranges`)) {
				const range = asRecord(rangeValue, `${id} range`);
				const constraint = asString(
					asRecord(range.version, `${id} range version`).constraint,
					`${id} constraint`,
				);
				if (!satisfiesConstraint(installed, constraint)) {
					continue;
				}
				const fix = range.fix === undefined ? undefined : asRecord(range.fix, `${id} fix`);
				matches.push({
					vulnerability: {
						id,
						severity: capitalized(asString(vulnerability.severity, `${id} severity`)),
						fix:
							fix?.state === "fixed"
								? { state: "fixed", versions: [asString(fix.version, `${id} fix version`)] }
								: { state: fix?.state ?? "unknown" },
					},
					artifact: { name: jvm.name, version: jvm.version },
				});
			}
		}
	}
	return matches;
}

/** The report with the JVM results appended. A report that already carries them is refused, not doubled. */
export function withJvmResults(
	reportInput: unknown,
	results: readonly Record<string, unknown>[],
): Record<string, unknown> {
	const report = asRecord(reportInput, "Trivy report");
	const existing = report.Results === undefined ? [] : asArray(report.Results, "Trivy results");
	if (existing.some((result) => isRecord(result) && result.Type === JVM_RESULT_TYPE)) {
		throw new Error("the Trivy report already carries JVM results");
	}
	return results.length === 0 ? report : { ...report, Results: [...existing, ...results] };
}

// `setup-release-security-tools` downloads the database once per job. A scan never downloads it, and
// refuses a database past the freshness bound.
const GRYPE_ENVIRONMENT = {
	GRYPE_CHECK_FOR_APP_UPDATE: "false",
	GRYPE_DB_AUTO_UPDATE: "false",
	GRYPE_DB_VALIDATE_AGE: "true",
	GRYPE_DB_MAX_ALLOWED_BUILT_AGE: GRYPE_DATABASE_MAX_AGE,
};

export interface JvmScan {
	readonly image: string;
	readonly platform: string;
	/** A platform manifest by digest. */
	readonly reference: string;
	/** The Trivy report of the same reference, which receives the JVM results. */
	readonly report: string;
	/** Whether the image was built from this checkout, so its JRE must be the pinned one. */
	readonly pinned: boolean;
}

export async function scanJvmRuntime(options: JvmScan): Promise<JvmPackage[]> {
	// The catalog and the matches stay out of the report's directory: release evidence publishes every
	// file there, and the appended result is the record.
	const scratch = await mkdtemp(path.join(tmpdir(), "jvm-runtime-"));
	try {
		return await scanInto(scratch, options);
	} finally {
		await rm(scratch, { recursive: true, force: true });
	}
}

async function scanInto(scratch: string, options: JvmScan): Promise<JvmPackage[]> {
	const sbom = path.join(scratch, "jvm.syft.json");
	await run("syft", [
		...syftImageArguments(options.reference, options.platform),
		"--override-default-catalogers",
		"java-jvm-cataloger",
		// The shared configuration adds the file catalogers, which read every file; this scan needs none.
		"--select-catalogers",
		"-file",
		"-o",
		`syft-json=${sbom}`,
	]);
	const packages = jvmPackages(await readJsonFile(sbom));
	const pin = JAVA_IMAGES[options.image];
	const pinnedVersion =
		options.pinned && pin !== undefined
			? asString(asRecord(await readJsonFile(pin), pin).version, `${pin} version`)
			: undefined;
	assertRuntimeInventory(options.image, packages, pinnedVersion);
	if (packages.length === 0) {
		return packages;
	}
	const grype = parseJson(
		await output("grype", [`sbom:${sbom}`, "-o", "json"], { env: GRYPE_ENVIRONMENT }),
	);
	// The affected packages of every vulnerability in the database for the JVMs' CPE products.
	const affected: unknown[] = [];
	for (const product of new Set(packages.flatMap((jvm) => jvm.cpes.map(cpeProduct)))) {
		const search = await output(
			"grype",
			["db", "search", "--pkg", `cpe:2.3:a:${product}`, "-o", "json"],
			{
				env: GRYPE_ENVIRONMENT,
			},
		);
		affected.push(...asArray(parseJson(search), `Grype database search for ${product}`));
	}
	const results = packages.map((jvm) =>
		jvmResult(
			{
				matches: [
					...asArray(asRecord(grype, "Grype report").matches, "Grype matches").filter(
						(match) =>
							isRecord(match) &&
							isRecord(match.artifact) &&
							match.artifact.name === jvm.name &&
							match.artifact.version === jvm.version,
					),
					...rangeMatches(affected, jvm),
				],
			},
			jvm,
		),
	);
	const report = withJvmResults(parseJson(await readFile(options.report, "utf8")), results);
	await writeFile(options.report, `${JSON.stringify(report, null, 2)}\n`);
	return packages;
}
