import { createHash } from "node:crypto";
import { appendFileSync, writeFileSync } from "node:fs";
import nodePath from "node:path";
import { parseArgs } from "node:util";

import {
	type ComponentEvidence,
	componentEvidence,
	imagePath,
	SYFT_COMPONENT_CATALOGERS,
	syftImageArguments,
} from "./check-release-sbom.ts";
import { isSet } from "./lib/env.ts";
import { asString, isRecord as record, readJsonFileSync } from "./lib/json.ts";
import { run } from "./lib/process.ts";

interface Finding {
	FixedVersion?: string;
	InstalledVersion: string;
	PkgName: string;
	Severity: string;
	/** The Trivy result the finding was reported in: a relative image path and its analyzer type. */
	Target: string;
	Type: string;
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

/**
 * An exception binds to the exact platform manifest (`digest`) it was reviewed against. Only a
 * code-absence claim may also name a reviewed component inventory captured from that manifest and the
 * official Go advisory it was reviewed against; it then follows byte-identical Go binaries into a
 * rebuilt image, and only while the advisory's published bytes are the reviewed ones.
 */
interface Exception {
	componentInventory?: string;
	digest: string;
	evidence: string;
	goAdvisory?: string;
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

/** Every Go binary of one image and platform, by absolute path and SHA-256, as captured from one manifest. */
interface ComponentInventory {
	captureDigest: string;
	files: { path: string; sha256: string }[];
	id: string;
	image: string;
	platform: string;
}

/** An official Go advisory as reviewed: its id and the SHA-256 of its published JSON bytes. */
interface GoAdvisory {
	id: string;
	sha256: string;
}

interface Policy {
	componentInventories: ComponentInventory[];
	exceptions: Exception[];
	goAdvisories: GoAdvisory[];
	schemaVersion: 3;
}

const GO_ADVISORY_ID = /^GO-\d{4}-\d{4,}$/u;

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
	["componentInventory", "goAdvisory"].every(
		(field) => value[field] === undefined || typeof value[field] === "string",
	) &&
	(value.status === "affected" || value.status === "not_affected") &&
	(value.justificationCategory === undefined ||
		isJustificationCategory(value.justificationCategory));

const isGoAdvisory = (value: unknown): value is GoAdvisory =>
	record(value) && typeof value.id === "string" && typeof value.sha256 === "string";

const isComponentInventory = (value: unknown): value is ComponentInventory =>
	record(value) &&
	["captureDigest", "id", "image", "platform"].every((field) => typeof value[field] === "string") &&
	Array.isArray(value.files) &&
	value.files.every(
		(file) => record(file) && typeof file.path === "string" && typeof file.sha256 === "string",
	);

function isHistoricalException(value: unknown): value is Exception {
	return (
		isException(value) && value.componentInventory === undefined && value.goAdvisory === undefined
	);
}

/**
 * The policy as a current gate reads it: schema 3 only. Verifying an existing signed bundle may also read
 * schema 2, whose every exception is bound to its own `digest` and so matches only that exact manifest.
 */
function readPolicy(value: unknown, historical: boolean): Policy {
	if (
		record(value) &&
		value.schemaVersion === 3 &&
		Array.isArray(value.componentInventories) &&
		value.componentInventories.every(isComponentInventory) &&
		Array.isArray(value.goAdvisories) &&
		value.goAdvisories.every(isGoAdvisory) &&
		Array.isArray(value.exceptions) &&
		value.exceptions.every(isException)
	) {
		return {
			componentInventories: value.componentInventories,
			exceptions: value.exceptions,
			goAdvisories: value.goAdvisories,
			schemaVersion: 3,
		};
	}
	if (
		historical &&
		record(value) &&
		value.schemaVersion === 2 &&
		Array.isArray(value.exceptions) &&
		value.exceptions.every(isHistoricalException)
	) {
		return {
			componentInventories: [],
			exceptions: value.exceptions,
			goAdvisories: [],
			schemaVersion: 3,
		};
	}
	throw new Error("malformed vulnerability policy");
}

function fingerprint(image: string, finding: Finding): string {
	return `${image}|${finding.VulnerabilityID}|${finding.PkgName}|${finding.InstalledVersion}`;
}

/** The findings with the result each came from, and every Go binary target the scan reported. */
function parseFindings(results: unknown[]): { findings: Finding[]; goTargets: string[] } {
	const findings: Finding[] = [];
	const goTargets: string[] = [];
	for (const result of results) {
		if (
			!record(result) ||
			(result.Vulnerabilities != null && !Array.isArray(result.Vulnerabilities))
		) {
			throw new Error("malformed Trivy result");
		}
		const target = typeof result.Target === "string" ? result.Target : "";
		const type = typeof result.Type === "string" ? result.Type : "";
		if (type === "gobinary") {
			goTargets.push(target);
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
				Target: target,
				Type: type,
				VulnerabilityID: vulnerability,
			});
		}
	}
	return { findings, goTargets };
}

function hasEveryField(exception: Exception): boolean {
	return (
		exception.owner.trim() !== "" &&
		exception.justification.trim() !== "" &&
		exception.expires !== "" &&
		exception.image !== "" &&
		exception.digest !== "" &&
		exception.componentInventory !== "" &&
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

function bindingErrors(
	exception: Exception,
	inventories: ReadonlyMap<string, ComponentInventory>,
	advisories: ReadonlySet<string>,
): string[] {
	const { componentInventory, digest, goAdvisory, vulnerability } = exception;
	if (!/^sha256:[a-f0-9]{64}$/u.test(digest)) {
		return [`malformed exception digest: ${digest}`];
	}
	if (componentInventory === undefined && goAdvisory === undefined) {
		return [];
	}
	if (componentInventory === undefined || goAdvisory === undefined) {
		return [`componentInventory and goAdvisory are named together: ${vulnerability}`];
	}
	// Byte-identical code only shows that code the review found absent is still absent; no other claim
	// survives a rebuild.
	if (
		exception.status !== "not_affected" ||
		exception.justificationCategory !== "vulnerable_code_not_present"
	) {
		return [
			`componentInventory is only for not_affected vulnerable_code_not_present exceptions: ${vulnerability}`,
		];
	}
	if (!advisories.has(goAdvisory)) {
		return [`exception names an unreviewed Go advisory: ${goAdvisory}`];
	}
	const inventory = inventories.get(componentInventory);
	if (inventory === undefined) {
		return [`exception names an unknown component inventory: ${componentInventory}`];
	}
	if (inventory.image !== exception.image || inventory.platform !== exception.platform) {
		return [
			`component inventory ${inventory.id} is for another image or platform: ${vulnerability}`,
		];
	}
	// The claim was reviewed on the manifest the inventory was captured from.
	if (inventory.captureDigest !== digest) {
		return [`exception ${vulnerability} was not reviewed on the manifest of ${inventory.id}`];
	}
	return [];
}

function advisoryErrors(advisories: readonly GoAdvisory[]): string[] {
	const errors: string[] = [];
	const ids = new Set<string>();
	for (const advisory of advisories) {
		if (!GO_ADVISORY_ID.test(advisory.id) || ids.has(advisory.id)) {
			errors.push(`Go advisory id is malformed or repeated: ${advisory.id}`);
		}
		if (!/^[a-f0-9]{64}$/u.test(advisory.sha256)) {
			errors.push(`Go advisory ${advisory.id} has a malformed SHA-256`);
		}
		ids.add(advisory.id);
	}
	return errors;
}

function inventoryErrors(inventories: readonly ComponentInventory[]): string[] {
	const errors: string[] = [];
	const ids = new Set<string>();
	const subjects = new Set<string>();
	for (const inventory of inventories) {
		const subject = `${inventory.image}|${inventory.platform}`;
		if (inventory.id === "" || ids.has(inventory.id)) {
			errors.push(`component inventory id is empty or repeated: ${inventory.id}`);
		}
		if (!/^sha256:[a-f0-9]{64}$/u.test(inventory.captureDigest)) {
			errors.push(`component inventory ${inventory.id} has a malformed capture digest`);
		}
		if (inventory.image === "" || subjects.has(subject)) {
			errors.push(`one component inventory per image and platform: ${subject}`);
		}
		if (!/^linux\/(?:amd64|arm64)$/u.test(inventory.platform)) {
			errors.push(`unsupported component inventory platform: ${inventory.platform}`);
		}
		ids.add(inventory.id);
		subjects.add(subject);
		const paths = new Set<string>();
		if (inventory.files.length === 0) {
			errors.push(`component inventory ${inventory.id} names no files`);
		}
		for (const file of inventory.files) {
			if (
				!file.path.startsWith("/") ||
				imagePath(file.path) !== file.path ||
				paths.has(file.path)
			) {
				errors.push(
					`component inventory ${inventory.id} has a malformed or repeated path: ${file.path}`,
				);
			}
			if (!/^[a-f0-9]{64}$/u.test(file.sha256)) {
				errors.push(`component inventory ${inventory.id} has a malformed SHA-256: ${file.path}`);
			}
			paths.add(file.path);
		}
	}
	return errors;
}

function exceptionErrors(exception: Exception, now: Date): string[] {
	const errors: string[] = [];
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
	const errors: string[] = [
		...inventoryErrors(policy.componentInventories),
		...advisoryErrors(policy.goAdvisories),
	];
	const inventories = new Map(
		policy.componentInventories.map((inventory) => [inventory.id, inventory]),
	);
	const advisories = new Set(policy.goAdvisories.map((advisory) => advisory.id));
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
		errors.push(
			...exceptionErrors(exception, now),
			...bindingErrors(exception, inventories, advisories),
		);
	}
	return errors;
}

interface Subject {
	digest: string;
	platform: string;
	reference: string;
}

/**
 * Whether this subject's Go code is exactly the reviewed inventory: every Go binary Syft catalogued and
 * every Go binary target Trivy reported, no more and no fewer, each with the one SHA-256 Syft recorded.
 */
function componentIdentityHolds(
	inventory: ComponentInventory,
	goTargets: readonly string[],
	subject: Subject,
	evidence: ComponentEvidence | undefined,
): boolean {
	if (
		evidence === undefined ||
		evidence.digest !== subject.digest ||
		evidence.platform !== subject.platform
	) {
		return false;
	}
	const targets = goTargets.map(imagePath);
	if (targets.some((target) => target === undefined)) {
		return false;
	}
	const current = new Set([
		...evidence.goBinaries,
		...targets.filter((target) => target !== undefined),
	]);
	const reviewed = new Map(inventory.files.map((file) => [file.path, file.sha256]));
	return (
		current.size === reviewed.size &&
		[...current].every((path) => {
			const hash = evidence.sha256.get(path);
			return typeof hash === "string" && hash === reviewed.get(path);
		})
	);
}

/**
 * Whether these are the published bytes of the reviewed official Go advisory, and it names this finding:
 * the reviewed SHA-256 of the raw bytes, the requested id at the JSON root, the finding's identifier among
 * its id and aliases, and the finding's package in the Go ecosystem. Nothing else in it is interpreted.
 */
function advisoryHolds(
	advisory: GoAdvisory,
	raw: Uint8Array | undefined,
	finding: Finding,
): boolean {
	if (raw === undefined || createHash("sha256").update(raw).digest("hex") !== advisory.sha256) {
		return false;
	}
	let document: unknown;
	try {
		document = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(raw));
	} catch {
		return false;
	}
	if (!record(document) || document.id !== advisory.id) {
		return false;
	}
	const aliases = Array.isArray(document.aliases) ? document.aliases : [];
	const affected = Array.isArray(document.affected) ? document.affected : [];
	return (
		(finding.VulnerabilityID === advisory.id || aliases.includes(finding.VulnerabilityID)) &&
		affected.some(
			(entry) =>
				record(entry) &&
				record(entry.package) &&
				entry.package.ecosystem === "Go" &&
				entry.package.name === finding.PkgName,
		)
	);
}

function isExcepted(
	policy: Policy,
	image: string,
	subject: Subject | undefined,
	finding: Finding,
	goTargets: readonly string[],
	evidence: ComponentEvidence | undefined,
	advisories: ReadonlyMap<string, Uint8Array>,
	now: Date,
): boolean {
	if (subject === undefined) {
		return false;
	}
	return policy.exceptions.some((exception) => {
		if (
			exception.image !== image ||
			exception.platform !== subject.platform ||
			exception.package !== finding.PkgName ||
			exception.installedVersion !== finding.InstalledVersion ||
			exception.vulnerability !== finding.VulnerabilityID ||
			new Date(exception.expires) <= now
		) {
			return false;
		}
		if (exception.componentInventory === undefined) {
			return exception.digest === subject.digest;
		}
		const inventory = policy.componentInventories.find(
			(candidate) => candidate.id === exception.componentInventory,
		);
		const advisory = policy.goAdvisories.find((candidate) => candidate.id === exception.goAdvisory);
		const target = imagePath(finding.Target);
		return (
			inventory !== undefined &&
			advisory !== undefined &&
			inventory.image === image &&
			inventory.platform === subject.platform &&
			inventory.captureDigest === exception.digest &&
			exception.status === "not_affected" &&
			exception.justificationCategory === "vulnerable_code_not_present" &&
			finding.Type === "gobinary" &&
			target !== undefined &&
			inventory.files.some((file) => file.path === target) &&
			componentIdentityHolds(inventory, goTargets, subject, evidence) &&
			advisoryHolds(advisory, advisories.get(advisory.id), finding)
		);
	});
}

/**
 * The official Go advisories an exception for this image and platform needs to be reused, or every one
 * any component-bound exception names when no subject is given. Empty when nothing needs component
 * evidence.
 */
export function componentAdvisories(
	policyValue: unknown,
	subject?: { image: string; platform: string },
	historical = false,
): string[] {
	const ids = new Set<string>();
	for (const exception of readPolicy(policyValue, historical).exceptions) {
		if (
			exception.componentInventory !== undefined &&
			exception.goAdvisory !== undefined &&
			(subject === undefined ||
				(exception.image === subject.image && exception.platform === subject.platform))
		) {
			ids.add(exception.goAdvisory);
		}
	}
	return [...ids].toSorted();
}

/** Where a release evidence bundle keeps the published bytes of one Go advisory, flat beside its reports. */
export function advisoryFile(id: string): string {
	if (!GO_ADVISORY_ID.test(id)) {
		throw new Error(`malformed Go advisory id: ${id}`);
	}
	return `advisory-${id}.json`;
}

const GO_ADVISORY_LIMIT_BYTES = 1024 * 1024;

/**
 * The published bytes of one official Go advisory: a single HTTPS request with no redirect, retry, cache
 * or credential, bounded in time and size. Anything else throws, and a gate without the bytes reuses
 * nothing.
 */
export async function fetchGoAdvisory(id: string): Promise<Uint8Array> {
	if (!GO_ADVISORY_ID.test(id)) {
		throw new Error(`malformed Go advisory id: ${id}`);
	}
	const response = await fetch(`https://vuln.go.dev/ID/${id}.json`, {
		redirect: "error",
		signal: AbortSignal.timeout(10_000),
	});
	if (response.status !== 200 || response.body === null) {
		throw new Error(`Go advisory ${id} answered ${response.status}`);
	}
	const chunks: Uint8Array[] = [];
	let size = 0;
	for await (const chunk of response.body) {
		size += chunk.byteLength;
		if (size > GO_ADVISORY_LIMIT_BYTES) {
			throw new Error(`Go advisory ${id} exceeds ${GO_ADVISORY_LIMIT_BYTES} bytes`);
		}
		chunks.push(chunk);
	}
	return Buffer.concat(chunks);
}

export function evaluate(
	image: string,
	reportValue: unknown,
	policyValue: unknown,
	now = new Date(),
	subject?: Subject,
	options: {
		/** The raw bytes of each official Go advisory a component-bound exception names, by id. */
		advisories?: ReadonlyMap<string, Uint8Array>;
		/** The subject's Syft component evidence, from {@link componentEvidence}. */
		evidence?: ComponentEvidence;
		/** Only verification of an existing signed bundle may read a schema 2 policy. */
		historical?: boolean;
	} = {},
) {
	if (!record(reportValue) || !Array.isArray(reportValue.Results)) {
		throw new Error("malformed Trivy report");
	}
	const policy = readPolicy(policyValue, options.historical === true);
	const errors: string[] = [];
	if (subject && reportValue.ArtifactName !== subject.reference) {
		errors.push(
			`Trivy report is for ${String(reportValue.ArtifactName)}, not ${subject.reference}`,
		);
	}
	errors.push(...policyErrors(policy, now));
	const { findings, goTargets } = parseFindings(reportValue.Results);
	const highCritical = findings.filter(
		(finding) => finding.Severity === "HIGH" || finding.Severity === "CRITICAL",
	);
	// Keep unfixable vulnerabilities in the evidence; only published fixes block release.
	const rejected = highCritical.filter(
		(finding) =>
			isSet(finding.FixedVersion?.trim()) &&
			!isExcepted(
				policy,
				image,
				subject,
				finding,
				goTargets,
				options.evidence,
				options.advisories ?? new Map(),
				now,
			),
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
	const { positionals, values } = parseArgs({
		options: { "no-annotations": { type: "boolean" } },
		allowPositionals: true,
	});
	const [image, platform, digest, repository, reportPath, policyPath, outputPath, syftPath] =
		positionals;
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
			"usage: check-release-vulnerabilities <image> <platform> <digest> <repository> <trivy.json> <policy.json> <result.json> [syft.json]",
		);
	}
	if (!/^linux\/(?:amd64|arm64)$/u.test(platform)) {
		throw new Error("unsupported platform");
	}
	if (!/^sha256:[a-f0-9]{64}$/u.test(digest)) {
		throw new Error("malformed subject digest");
	}
	const reference = `${repository}@${digest}`;
	const policy = readJsonFileSync(policyPath);
	// Component identity and the official Go advisories are read only where an exception for this subject
	// depends on them, and always fresh: an archived advisory is never current. Both are kept beside the
	// report; a scan or fetch that fails, or reads another subject, grants nothing.
	const stem = nodePath.join(nodePath.dirname(reportPath), nodePath.basename(reportPath, ".json"));
	const advisoryIds = componentAdvisories(policy, { image, platform });
	let identity = syftPath;
	if (!isSet(identity) && advisoryIds.length > 0) {
		identity = `${stem}.syft.json`;
		await run("syft", [
			...syftImageArguments(reference, platform),
			...SYFT_COMPONENT_CATALOGERS,
			"-o",
			`syft-json=${identity}`,
		]);
	}
	const advisories = new Map<string, Uint8Array>();
	for (const id of advisoryIds) {
		const raw = await fetchGoAdvisory(id);
		writeFileSync(`${stem}.${id}.json`, raw);
		advisories.set(id, raw);
	}
	const result = evaluate(
		image,
		readJsonFileSync(reportPath),
		policy,
		new Date(),
		{
			digest,
			platform,
			reference,
		},
		{
			advisories,
			evidence: isSet(identity)
				? componentEvidence(readJsonFileSync(identity), { repository, digest, platform })
				: undefined,
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
			process.stderr.write(`${values["no-annotations"] === true ? "" : "::error::"}${message}\n`);
		}
		process.exitCode = 1;
	}
}
