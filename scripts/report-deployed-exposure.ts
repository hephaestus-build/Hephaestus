/**
 * Keeps one issue in step with what the deployed images are exposed to.
 *
 * The release policy blocks a fixable HIGH or CRITICAL finding at build and release time. For a running
 * host, three things need a person at once, whatever the fix state:
 *
 * - a CVE that CISA lists as known exploited (KEV),
 * - a CRITICAL finding,
 * - a finding the policy refuses.
 *
 * This reads every Trivy report and policy result that `scan-deployed-images.ts` and
 * `rescan-release-images.ts` wrote, intersects the CVEs with the KEV catalog, and creates, edits or
 * closes one issue. The issue carries a fingerprint of the finding set, so an unchanged set
 * notifies nobody, as `report-vulnerability-drift.ts` does.
 */
import { createHash } from "node:crypto";
import { glob, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet, requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { fetchKev, type KevCatalog } from "./lib/kev.ts";
import { output, run } from "./lib/process.ts";
import {
	applyPlan,
	type IssuePlan,
	markerFingerprint,
	parsePolicyResult,
	type TrackingIssue,
	workflowIssues,
} from "./report-vulnerability-drift.ts";

export const EXPOSURE_MARKER = "hephaestus:deployed-image-exposure";
export const EXPOSURE_TITLE =
	"A deployed image has an exploited, critical or refused vulnerability";

export interface Exposure {
	/** `repository@digest` of the scanned platform manifest. */
	readonly subject: string;
	readonly vulnerability: string;
	readonly package: string;
	readonly installedVersion: string;
	readonly severity: string;
	readonly fixedVersion?: string;
	/** Why the finding is listed. */
	readonly reasons: readonly ("kev" | "critical" | "refused")[];
	/** The KEV due date, when CISA lists it. */
	readonly kevDueDate?: string;
}

interface Finding {
	readonly subject: string;
	readonly vulnerability: string;
	readonly package: string;
	readonly installedVersion: string;
	readonly severity: string;
	readonly fixedVersion?: string;
}

/** The findings of one Trivy report, the appended JVM result included. */
export function reportFindings(value: unknown): Finding[] {
	const report = asRecord(value, "Trivy report");
	const subject = asString(report.ArtifactName, "Trivy report ArtifactName");
	const results = report.Results === undefined ? [] : asArray(report.Results, "Trivy results");
	return results.flatMap((result, index) => {
		const record = asRecord(result, `Trivy results[${index}]`);
		const vulnerabilities =
			record.Vulnerabilities == null ? [] : asArray(record.Vulnerabilities, "Vulnerabilities");
		return vulnerabilities.map((item) => {
			const finding = asRecord(item, "Trivy vulnerability");
			return {
				subject,
				vulnerability: asString(finding.VulnerabilityID, "VulnerabilityID"),
				package: asString(finding.PkgName, "PkgName"),
				installedVersion: asString(finding.InstalledVersion, "InstalledVersion"),
				severity: asString(finding.Severity, "Severity"),
				...(typeof finding.FixedVersion === "string" && finding.FixedVersion !== ""
					? { fixedVersion: finding.FixedVersion }
					: {}),
			};
		});
	});
}

/**
 * The findings that need a response. `refused` holds the policy's rejected fingerprints,
 * `image|vulnerability|package|installed`, by the platform digest they were judged on.
 */
export function exposures(
	findings: readonly Finding[],
	kev: KevCatalog,
	refused: ReadonlyMap<string, ReadonlySet<string>>,
): Exposure[] {
	const listed = new Map<string, Exposure>();
	for (const finding of findings) {
		const entry = kev.entries.get(finding.vulnerability);
		const rejected = refused.get(finding.subject.slice(finding.subject.indexOf("@") + 1));
		const reasons: Exposure["reasons"][number][] = [];
		if (entry !== undefined) {
			reasons.push("kev");
		}
		if (finding.severity === "CRITICAL") {
			reasons.push("critical");
		}
		if (
			rejected !== undefined &&
			[...rejected].some((fingerprint) =>
				fingerprint.endsWith(
					`|${finding.vulnerability}|${finding.package}|${finding.installedVersion}`,
				),
			)
		) {
			reasons.push("refused");
		}
		if (reasons.length === 0) {
			continue;
		}
		const key = `${finding.subject}|${finding.vulnerability}|${finding.package}|${finding.installedVersion}`;
		listed.set(key, {
			...finding,
			reasons,
			...(entry === undefined ? {} : { kevDueDate: entry.dueDate }),
		});
	}
	return [...listed.values()].toSorted((left, right) =>
		`${left.subject}|${left.vulnerability}|${left.package}`.localeCompare(
			`${right.subject}|${right.vulnerability}|${right.package}`,
		),
	);
}

export function exposureFingerprint(listed: readonly Exposure[]): string {
	return createHash("sha256").update(JSON.stringify(listed)).digest("hex").slice(0, 16);
}

export interface ExposureContext {
	readonly kevVersion: string;
	readonly runUrl: string;
	readonly scannedAt: string;
	readonly targets: string;
}

export function renderExposureBody(listed: readonly Exposure[], context: ExposureContext): string {
	const lines = [
		`<!-- ${EXPOSURE_MARKER} ${exposureFingerprint(listed)} -->`,
		"",
		"@hephaestus-build/maintainer: an image that a project host runs has a vulnerability that needs a response now.",
		"",
		`Scanned ${context.targets} on ${context.scannedAt}, against CISA KEV catalog ${context.kevVersion}. Findings last changed by [this run](${context.runUrl}).`,
		"",
		"| Subject | Vulnerability | Package | Installed | Severity | Fixed in | Why | KEV due |",
		"| --- | --- | --- | --- | --- | --- | --- | --- |",
		...listed.map(
			(item) =>
				`| \`${item.subject}\` | ${item.vulnerability} | ${item.package} | ${item.installedVersion} | ${item.severity} | ${item.fixedVersion ?? "no fix"} | ${item.reasons.join(", ")} | ${item.kevDueDate ?? ""} |`,
		),
		"",
		"A KEV listing means CISA has evidence of exploitation: mitigate or patch first, whatever the severity.",
		"For a finding without a fix, record the mitigation here, then roll out a fixed build as soon as one exists. This issue stays open until no deployed image carries the finding (docs/contributor/vulnerability-remediation.mdx § Deployed image rescans).",
		"",
		"Maintained by the `deployed` job of `.github/workflows/rescan-images.yml`. It edits this issue when the finding set changes and closes it when the set is empty. Keep the HTML comment on the first line.",
		"",
	];
	return lines.join("\n");
}

export function planExposureIssue(
	open: readonly TrackingIssue[],
	listed: readonly Exposure[],
	context: ExposureContext,
): IssuePlan {
	const tracked = open
		.filter((issue) => markerFingerprint(issue.body, EXPOSURE_MARKER) !== undefined)
		.toSorted((left, right) => left.number - right.number);
	if (listed.length === 0) {
		return tracked.length === 0
			? { kind: "none" }
			: {
					comment: `No deployed image has a KEV-listed, CRITICAL or refused finding as of ${context.scannedAt}. Closed by [this run](${context.runUrl}).`,
					issues: tracked.map((issue) => issue.number),
					kind: "close",
				};
	}
	const body = renderExposureBody(listed, context);
	const [current] = tracked;
	if (current === undefined) {
		return { body, kind: "create", title: EXPOSURE_TITLE };
	}
	if (markerFingerprint(current.body, EXPOSURE_MARKER) === exposureFingerprint(listed)) {
		return { issue: current.number, kind: "unchanged" };
	}
	return {
		body,
		comment: `@hephaestus-build/maintainer: the deployed finding set changed as of ${context.scannedAt}; the description lists what [this run](${context.runUrl}) found.`,
		issue: current.number,
		kind: "update",
	};
}

/** Every Trivy report and policy result under the given directories. */
async function readReports(directories: readonly string[]) {
	const findings: Finding[] = [];
	const refused = new Map<string, Set<string>>();
	for (const directory of directories) {
		for await (const file of glob(path.join(directory, "**", "*.json"))) {
			const value = parseJson(await readFile(file, "utf8"));
			if (file.endsWith(".policy.json")) {
				const result = parsePolicyResult(value, file);
				refused.set(
					result.digest,
					new Set([...(refused.get(result.digest) ?? []), ...result.rejected]),
				);
				continue;
			}
			if (isRecord(value) && value.SchemaVersion === 2 && typeof value.ArtifactName === "string") {
				findings.push(...reportFindings(value));
			}
		}
	}
	return { findings, refused };
}

if (import.meta.main) {
	const directories = process.argv.slice(2);
	if (directories.length === 0) {
		throw new Error("usage: report-deployed-exposure <reports>...");
	}
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const kev = await fetchKev();
	const { findings, refused } = await readReports(directories);
	if (findings.length === 0 && refused.size === 0) {
		throw new Error(`no scan reports under ${directories.join(", ")}`);
	}
	const listed = exposures(findings, kev, refused);
	const context: ExposureContext = {
		kevVersion: kev.catalogVersion,
		runUrl: `${process.env.GITHUB_SERVER_URL ?? "https://github.com"}/${repository}/actions/runs/${process.env.GITHUB_RUN_ID ?? ""}`,
		scannedAt: new Date().toISOString().replace(/\.\d+Z$/u, "Z"),
		targets: process.env.DEPLOYED_TARGETS ?? "the deployed images",
	};
	const open = workflowIssues(
		parseJson(
			await output("gh", [
				"issue",
				"list",
				"--repo",
				repository,
				"--state",
				"open",
				"--json",
				"author,body,number",
				"--limit",
				"500",
			]),
		),
	);
	const summary = await applyPlan(planExposureIssue(open, listed, context), async (args) =>
		run("gh", [...args, "--repo", repository]),
	);
	process.stdout.write(`${summary}\n`);
	const summaryPath = process.env.GITHUB_STEP_SUMMARY;
	if (isSet(summaryPath)) {
		await writeFile(summaryPath, `### Deployed image exposure\n\n${summary}\n`, { flag: "a" });
	}
}
