import { appendFileSync, writeFileSync } from "node:fs";

import { isSet } from "./lib/env.ts";
import { asString, isRecord as record, readJsonFileSync } from "./lib/json.ts";

interface Finding {
	FixedVersion?: string;
	InstalledVersion: string;
	PkgName: string;
	Severity: string;
	VulnerabilityID: string;
}

// CISA VEX justification vocabulary; policy: docs/contributor/vulnerability-remediation.mdx.
const JUSTIFICATION_CATEGORIES = [
	"component_not_present",
	"vulnerable_code_not_present",
	"vulnerable_code_not_in_execute_path",
	"vulnerable_code_cannot_be_controlled_by_adversary",
	"inline_mitigations_already_exist",
] as const;

export type JustificationCategory = (typeof JUSTIFICATION_CATEGORIES)[number];

const isJustificationCategory = (value: unknown): value is JustificationCategory =>
	JUSTIFICATION_CATEGORIES.some((category) => category === value);

// Match package versions, not build digests: exceptions must be authorable before release signing.
interface Exception {
	digest: string;
	evidence: string;
	expires: string;
	image: string;
	installedVersion: string;
	justification: string;
	/** Required when `status` is `not_affected`, and forbidden otherwise. */
	justificationCategory?: JustificationCategory;
	owner: string;
	package: string;
	platform: string;
	status: "affected" | "not_affected";
	vulnerability: string;
}
interface Policy {
	exceptions: Exception[];
	schemaVersion: 2;
}

const isException = (value: unknown): value is Exception =>
	record(value) &&
	[
		"digest",
		"evidence",
		"expires",
		"image",
		"installedVersion",
		"justification",
		"owner",
		"package",
		"platform",
		"status",
		"vulnerability",
	].every((field) => typeof value[field] === "string") &&
	(value.status === "affected" || value.status === "not_affected") &&
	(value.justificationCategory === undefined ||
		isJustificationCategory(value.justificationCategory));

const isPolicy = (value: unknown): value is Policy =>
	record(value) &&
	value.schemaVersion === 2 &&
	Array.isArray(value.exceptions) &&
	value.exceptions.every(isException);

function fingerprint(image: string, finding: Finding): string {
	return `${image}|${finding.VulnerabilityID}|${finding.PkgName}|${finding.InstalledVersion}`;
}

function parseFindings(results: unknown[]): Finding[] {
	const findings: Finding[] = [];
	for (const result of results) {
		if (
			!record(result) ||
			(result.Vulnerabilities != null && !Array.isArray(result.Vulnerabilities))
		) {
			throw new Error("malformed Trivy result");
		}
		for (const value of result.Vulnerabilities ?? []) {
			if (!record(value)) {
				throw new Error("malformed Trivy vulnerability");
			}
			const required = (field: string) => {
				const text = asString(value[field], `Trivy vulnerability ${field}`);
				if (!text.trim()) {
					throw new Error(`malformed Trivy vulnerability: empty ${field}`);
				}
				return text;
			};
			const installedVersion = required("InstalledVersion");
			const packageName = required("PkgName");
			const severity = required("Severity");
			const vulnerability = required("VulnerabilityID");
			if (!["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"].includes(severity)) {
				throw new Error(`malformed Trivy vulnerability: unsupported Severity ${severity}`);
			}
			// Trivy omits FixedVersion when upstream has published no fix.
			const fixedVersion =
				value.FixedVersion === undefined
					? undefined
					: asString(value.FixedVersion, "Trivy vulnerability FixedVersion");
			findings.push({
				FixedVersion: fixedVersion,
				InstalledVersion: installedVersion,
				PkgName: packageName,
				Severity: severity,
				VulnerabilityID: vulnerability,
			});
		}
	}
	return findings;
}

function hasEveryField(exception: Exception): boolean {
	return (
		exception.owner.trim() !== "" &&
		exception.justification.trim() !== "" &&
		exception.expires !== "" &&
		exception.image !== "" &&
		exception.digest !== "" &&
		exception.evidence !== "" &&
		exception.installedVersion !== "" &&
		exception.package !== "" &&
		exception.platform !== "" &&
		exception.vulnerability !== ""
	);
}

function isHttpsUrl(value: string): boolean {
	try {
		return new URL(value).protocol === "https:";
	} catch {
		return false;
	}
}

function expiryError(exception: Exception, now: Date): string | undefined {
	const expiry = new Date(exception.expires);
	if (
		!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u.test(exception.expires) ||
		Number.isNaN(expiry.valueOf()) ||
		expiry.toISOString() !== exception.expires.replace("Z", ".000Z") ||
		expiry <= now
	) {
		return `invalid or expired exception: ${exception.vulnerability} (${exception.expires})`;
	}
	if (expiry.valueOf() - now.valueOf() > 90 * 24 * 60 * 60 * 1000) {
		return `exception exceeds 90-day limit: ${exception.vulnerability} (${exception.expires})`;
	}
	return undefined;
}

function exceptionErrors(exception: Exception, now: Date): string[] {
	const errors: string[] = [];
	if (!/^sha256:[a-f0-9]{64}$/u.test(exception.digest)) {
		errors.push(`malformed exception digest: ${exception.digest}`);
	}
	if (!/^linux\/(?:amd64|arm64)$/u.test(exception.platform)) {
		errors.push(`unsupported exception platform: ${exception.platform}`);
	}
	if (exception.status === "not_affected" && exception.justificationCategory === undefined) {
		errors.push(
			`not_affected exception must name a justification category: ${exception.vulnerability}`,
		);
	}
	if (exception.status === "affected" && exception.justificationCategory !== undefined) {
		errors.push(
			`affected exception must not name a justification category: ${exception.vulnerability}`,
		);
	}
	if (!isHttpsUrl(exception.evidence)) {
		errors.push(`exception evidence must be an HTTPS URL: ${exception.evidence}`);
	}
	const expiry = expiryError(exception, now);
	if (expiry !== undefined) {
		errors.push(expiry);
	}
	return errors;
}

function policyErrors(policy: Policy, now: Date): string[] {
	const errors: string[] = [];
	const exceptionKeys = new Set<string>();
	for (const exception of policy.exceptions) {
		if (!hasEveryField(exception)) {
			errors.push(
				"exception is missing subject, status, evidence, owner, justification, expiry, package, installed version, or vulnerability",
			);
			continue;
		}
		const key = `${exception.image}|${exception.platform}|${exception.vulnerability}|${exception.package}|${exception.installedVersion}`;
		if (exceptionKeys.has(key)) {
			errors.push(`duplicate exception: ${key}`);
		}
		exceptionKeys.add(key);
		errors.push(...exceptionErrors(exception, now));
	}
	return errors;
}

function isExcepted(
	policy: Policy,
	image: string,
	platform: string | undefined,
	finding: Finding,
	now: Date,
): boolean {
	return policy.exceptions.some(
		(exception) =>
			exception.image === image &&
			exception.platform === platform &&
			exception.package === finding.PkgName &&
			exception.installedVersion === finding.InstalledVersion &&
			exception.vulnerability === finding.VulnerabilityID &&
			new Date(exception.expires) > now,
	);
}

export function evaluate(
	image: string,
	reportValue: unknown,
	policyValue: unknown,
	now = new Date(),
	subject?: { digest: string; platform: string; reference: string },
) {
	if (!record(reportValue) || !Array.isArray(reportValue.Results)) {
		throw new Error("malformed Trivy report");
	}
	if (!isPolicy(policyValue)) {
		throw new Error("malformed vulnerability policy");
	}
	const policy = policyValue;
	const errors: string[] = [];
	if (subject && reportValue.ArtifactName !== subject.reference) {
		errors.push(
			`Trivy report is for ${String(reportValue.ArtifactName)}, not ${subject.reference}`,
		);
	}
	errors.push(...policyErrors(policy, now));
	const highCritical = parseFindings(reportValue.Results).filter(
		(finding) => finding.Severity === "HIGH" || finding.Severity === "CRITICAL",
	);
	// Keep unfixable vulnerabilities in the evidence; only published fixes block release.
	const rejected = highCritical.filter(
		(finding) =>
			isSet(finding.FixedVersion?.trim()) &&
			!isExcepted(policy, image, subject?.platform, finding, now),
	);
	return {
		highCritical: highCritical.map((finding) => fingerprint(image, finding)),
		errors,
		rejected: rejected.map((finding) => fingerprint(image, finding)),
	};
}

export type Evaluation = ReturnType<typeof evaluate>;

export function renderSummary(
	subject: { digest: string; image: string; platform: string },
	result: Evaluation,
): string {
	const status = result.errors.length === 0 && result.rejected.length === 0 ? "pass" : "fail";
	const lines = [
		`### Vulnerability policy — ${subject.image} (${subject.platform}): ${status}`,
		"",
		`Subject \`${subject.digest}\` — ${result.highCritical.length} HIGH/CRITICAL, ${result.rejected.length} rejected.`,
	];
	if (result.rejected.length > 0) {
		lines.push("", "| Vulnerability | Package | Installed |", "| --- | --- | --- |");
		for (const finding of result.rejected) {
			const [, vulnerability = "", packageName = "", installedVersion = ""] = finding.split("|");
			lines.push(`| ${vulnerability} | ${packageName} | ${installedVersion} |`);
		}
	}
	if (result.errors.length > 0) {
		lines.push("", ...result.errors.map((message) => `- ${message}`));
	}
	return `${lines.join("\n")}\n`;
}

if (import.meta.main) {
	const [image, platform, digest, repository, reportPath, policyPath, outputPath] =
		process.argv.slice(2);
	if (
		!isSet(image) ||
		!isSet(platform) ||
		!isSet(digest) ||
		!isSet(repository) ||
		!isSet(reportPath) ||
		!isSet(policyPath) ||
		!isSet(outputPath)
	) {
		throw new Error(
			"usage: check-release-vulnerabilities <image> <platform> <digest> <repository> <trivy.json> <policy.json> <result.json>",
		);
	}
	if (!/^linux\/(?:amd64|arm64)$/u.test(platform)) {
		throw new Error("unsupported platform");
	}
	if (!/^sha256:[a-f0-9]{64}$/u.test(digest)) {
		throw new Error("malformed subject digest");
	}
	const reference = `${repository}@${digest}`;
	const result = evaluate(
		image,
		readJsonFileSync(reportPath),
		readJsonFileSync(policyPath),
		new Date(),
		{
			digest,
			platform,
			reference,
		},
	);
	writeFileSync(
		outputPath,
		`${JSON.stringify({ image, platform, digest, status: result.errors.length === 0 && result.rejected.length === 0 ? "pass" : "fail", ...result }, null, 2)}\n`,
	);
	const summaryPath = process.env.GITHUB_STEP_SUMMARY;
	if (isSet(summaryPath)) {
		appendFileSync(summaryPath, renderSummary({ digest, image, platform }, result));
	}
	if (result.errors.length > 0 || result.rejected.length > 0) {
		for (const message of [
			...result.errors,
			...result.rejected.map((finding) => `undispositioned finding: ${finding}`),
		]) {
			process.stderr.write(`::error::${message}\n`);
		}
		process.exitCode = 1;
	}
}
