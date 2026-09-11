/**
 * Scans one platform of a set of container images against `security/vulnerability-policy.json`.
 *
 * Two callers plan subjects and share everything after that: `scan-main-images.ts` resolves the
 * first-party `:main` tags, and `scan-upstream-images.ts` reads the digests pinned in
 * `security/release-images.json`. Both hand the Trivy report to `check-release-vulnerabilities.ts`
 * — the same evaluator and the same policy file the build gate and the release use. A second copy
 * of either is precisely the failure this whole effort removes, so the policy is evaluated by
 * invoking that script rather than by reimplementing it. The blocking build scan shares only the
 * platform-digest resolver through `resolve-image-scan-subject.ts`.
 */
import { existsSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import path from "node:path";

import { asRecord, isRecord } from "./json.ts";
import { output, run, succeeds } from "./process.ts";

/** The platform a caller gets when it names none. Callers that must match the release evidence
 * gate, which is keyed per platform, name both — see `scan-upstream-images.ts`. */
export const PLATFORM = "linux/amd64";

const DIGEST = /^sha256:[a-f0-9]{64}$/;

export interface Subject {
	/** Short image name, as `security/release-images.json` and the policy exceptions spell it. */
	readonly image: string;
	readonly reference: string;
	readonly repository: string;
}

export interface ScanOutcome {
	readonly image: string;
	/** `false` when the policy evaluator rejected something; never throws the run. */
	readonly passed: boolean;
	/** Carried because the policy match key is per platform, so an outcome that does not name one
	 * cannot be reported or compared against the release gate's subjects. */
	readonly platform: string;
}

export interface ScanOptions {
	/**
	 * Let the evaluator's `::error::` annotations reach the log. A blocking gate needs them to say
	 * what it rejected; a scheduled rescan routes findings to a tracking issue instead, and a green
	 * job carrying error annotations is how a team learns to read past them.
	 */
	readonly annotate?: boolean;
	readonly platform?: string;
}

/**
 * The requested platform's digest inside `docker buildx imagetools inspect --raw` output, or
 * `undefined` when the document is a single manifest rather than an index and the caller must ask
 * for its digest directly. Multi-architecture builds also push an attestation manifest, whose
 * platform is `unknown/unknown`, so the architecture has to be matched rather than the position
 * assumed.
 */
export function selectPlatformDigest(raw: unknown, platform: string): string | undefined {
	const document = asRecord(raw, "imagetools manifest");
	if (!Array.isArray(document.manifests)) return undefined;
	const [os, architecture] = platform.split("/");
	for (const entry of document.manifests) {
		if (!isRecord(entry) || !isRecord(entry.platform)) continue;
		if (entry.platform.os !== os || entry.platform.architecture !== architecture) continue;
		if (typeof entry.digest === "string") return entry.digest;
	}
	return undefined;
}

/** Whether the inspected document is a multi-platform index rather than a single manifest. */
export function isImageIndex(raw: unknown): boolean {
	const document = asRecord(raw, "imagetools manifest");
	if (Object.hasOwn(document, "manifests") && !Array.isArray(document.manifests))
		throw new Error("imagetools index manifests must be an array");
	return Array.isArray(document.manifests);
}

export async function resolvePlatformDigest(reference: string, platform: string): Promise<string> {
	const raw: unknown = JSON.parse(
		await output("docker", ["buildx", "imagetools", "inspect", reference, "--raw"]),
	);
	const selected = selectPlatformDigest(raw, platform);
	// An index that publishes no manifest for this platform must fail, never fall through. The
	// fallback below returns the *index* digest, and Trivy resolves a multi-platform reference
	// against the host it runs on — so the scan would quietly be `linux/amd64` while being filed as
	// this platform's evidence, and the evaluator's ArtifactName check cannot catch it, because the
	// reference Trivy reports is exactly the one it was handed.
	if (selected === undefined && isImageIndex(raw))
		throw new Error(`${reference} publishes no ${platform} manifest`);
	const digest =
		selected ??
		(
			await output("docker", [
				"buildx",
				"imagetools",
				"inspect",
				"--format",
				"{{.Manifest.Digest}}",
				reference,
			])
		).trim();
	if (!DIGEST.test(digest))
		throw new Error(`no ${platform} digest for ${reference} (got: ${digest || "<empty>"})`);
	return digest;
}

/** `webapp-linux-amd64`, the stem both report files and the uploaded artifact are named by. */
export function reportStem(image: string, platform: string): string {
	return `${image}-${platform.replaceAll("/", "-")}`;
}

async function evaluatorPassed(evaluator: string[], annotate: boolean): Promise<boolean> {
	if (!annotate) return succeeds("node", evaluator);
	try {
		await run("node", evaluator);
		return true;
	} catch {
		return false;
	}
}

async function scan(
	subject: Subject,
	directory: string,
	platform: string,
	annotate: boolean,
): Promise<ScanOutcome> {
	const digest = await resolvePlatformDigest(subject.reference, platform);
	const stem = reportStem(subject.image, platform);
	const report = path.join(directory, `${stem}.json`);
	await run("trivy", [
		"image",
		"--skip-db-update",
		"--scanners",
		"vuln",
		"--format",
		"json",
		"--output",
		report,
		`${subject.repository}@${digest}`,
	]);
	const result = path.join(directory, `${stem}.policy.json`);
	const evaluator = [
		path.join(import.meta.dirname, "..", "check-release-vulnerabilities.ts"),
		subject.image,
		platform,
		digest,
		subject.repository,
		report,
		"security/vulnerability-policy.json",
		result,
	];
	if (await evaluatorPassed(evaluator, annotate))
		return { image: subject.image, passed: true, platform };
	if (!existsSync(result)) {
		// It threw before writing anything — a malformed report or policy, not a finding. Re-run so
		// the reason reaches the log, then fail: this is an infrastructure failure, and unlike a CVE
		// it is fixed by a commit.
		await run("node", evaluator);
		throw new Error(`vulnerability policy evaluation produced no result for ${subject.image}`);
	}
	return { image: subject.image, passed: false, platform };
}

export async function scanAll(
	subjects: readonly Subject[],
	directory: string,
	options: ScanOptions = {},
): Promise<ScanOutcome[]> {
	await mkdir(directory, { recursive: true });
	const platform = options.platform ?? PLATFORM;
	const outcomes: ScanOutcome[] = [];
	for (const subject of subjects)
		outcomes.push(await scan(subject, directory, platform, options.annotate ?? false));
	return outcomes;
}
