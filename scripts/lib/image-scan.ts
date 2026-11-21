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
import { mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { asRecord, isRecord } from "./json.ts";
import { scanJvmRuntime } from "./jvm-runtime.ts";
import { output, run } from "./process.ts";
import {
	assertKnownPackagesScanned,
	extractWebApp,
	RUNNER_JAR,
	WEB_APP_IMAGE,
	WEB_APP_STEM,
	webAppTrivyArguments,
} from "./web-app-scan.ts";

/** The platform a caller gets when it names none. Callers that must match the release evidence
 * gate, which is keyed per platform, name both — see `scan-upstream-images.ts`. */
export const PLATFORM = "linux/amd64";

const DIGEST = /^sha256:[a-f0-9]{64}$/u;

export interface Subject {
	/** Short image name, as `security/release-images.json` and the policy exceptions spell it. */
	readonly image: string;
	readonly reference: string;
	/** An authenticated platform leaf, when the caller already resolved it. */
	readonly digest?: string;
	readonly repository: string;
}

export interface ScanOutcome {
	readonly image: string;
	/** False for a policy refusal or, in a continuing scan, an infrastructure error. */
	readonly passed: boolean;
	/** An unavailable scan, distinct from a completed policy refusal. */
	readonly error?: string;
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
	/** Collect every subject even when one scan is unavailable. Existing callers remain fail-fast. */
	readonly continueOnError?: boolean;
	/** The server image must carry the web app, as every image built since the web app moved into it does. */
	readonly requireWebApp?: boolean;
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
	if (!Array.isArray(document.manifests)) {
		return undefined;
	}
	const [os, architecture] = platform.split("/");
	for (const entry of document.manifests) {
		if (!isRecord(entry) || !isRecord(entry.platform)) {
			continue;
		}
		if (entry.platform.os !== os || entry.platform.architecture !== architecture) {
			continue;
		}
		if (typeof entry.digest === "string") {
			return entry.digest;
		}
	}
	return undefined;
}

/** Whether the inspected document is a multi-platform index rather than a single manifest. */
export function isImageIndex(raw: unknown): boolean {
	const document = asRecord(raw, "imagetools manifest");
	if (Object.hasOwn(document, "manifests") && !Array.isArray(document.manifests)) {
		throw new Error("imagetools index manifests must be an array");
	}
	return Array.isArray(document.manifests);
}

/** The digest of the manifest `reference` names, which for an index is the index itself. */
export async function manifestDigest(reference: string, signal: AbortSignal): Promise<string> {
	const inspected = await output(
		"docker",
		["buildx", "imagetools", "inspect", "--format", "{{.Manifest.Digest}}", reference],
		{ signal },
	);
	return inspected.trim();
}

export async function resolvePlatformDigest(reference: string, platform: string): Promise<string> {
	// Both registry reads share one deadline, including the single-manifest fallback.
	const signal = AbortSignal.timeout(120_000);
	const raw: unknown = JSON.parse(
		await output("docker", ["buildx", "imagetools", "inspect", reference, "--raw"], { signal }),
	);
	const selected = selectPlatformDigest(raw, platform);
	// An index that publishes no manifest for this platform must fail, never fall through. The
	// fallback below returns the *index* digest, and Trivy resolves a multi-platform reference
	// against the host it runs on — so the scan would quietly be `linux/amd64` while being filed as
	// this platform's evidence, and the evaluator's ArtifactName check cannot catch it, because the
	// reference Trivy reports is exactly the one it was handed.
	if (selected === undefined && isImageIndex(raw)) {
		throw new Error(`${reference} publishes no ${platform} manifest`);
	}
	const digest = selected ?? (await manifestDigest(reference, signal));
	if (!DIGEST.test(digest)) {
		throw new Error(`no ${platform} digest for ${reference} (got: ${digest || "<empty>"})`);
	}
	return digest;
}

/** `server-linux-amd64`, the stem both report files and the uploaded artifact are named by. */
export function reportStem(image: string, platform: string): string {
	return `${image}-${platform.replaceAll("/", "-")}`;
}

async function evaluatorPassed(evaluator: string[], annotate: boolean): Promise<boolean> {
	try {
		await run("node", annotate ? evaluator : [...evaluator, "--no-annotations"]);
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
	requireWebApp: boolean,
): Promise<ScanOutcome> {
	const digest = subject.digest ?? (await resolvePlatformDigest(subject.reference, platform));
	if (
		!DIGEST.test(digest) ||
		(subject.digest !== undefined && subject.reference !== `${subject.repository}@${digest}`)
	) {
		throw new Error(`scan subject is not bound to its platform digest: ${subject.image}`);
	}
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
	// Trivy does not see a JRE that a tarball installed; this appends the runtime's findings.
	await scanJvmRuntime({
		image: subject.image,
		platform,
		reference: `${subject.repository}@${digest}`,
		report,
		pinned: false,
	});
	const result = path.join(directory, `${stem}.policy.json`);
	await rm(result, { force: true });
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
	const imagePassed = await evaluatorPassed(evaluator, annotate);
	if (!imagePassed && !existsSync(result)) {
		// Native acquisition errors already reached stderr on the first invocation. Never repeat a scan.
		throw new Error(`vulnerability policy evaluation produced no result for ${subject.image}`);
	}
	// A server image older than the web app's move into it carries none; the release evidence requires it.
	const webAppPassed =
		subject.image === WEB_APP_IMAGE
			? await scanWebApp(subject, digest, platform, path.join(directory, stem), {
					annotate,
					required: requireWebApp,
				})
			: true;
	return { image: subject.image, passed: imagePassed && webAppPassed !== false, platform };
}

/**
 * The web app inside the server image's jar, which the image scan cannot see (`web-app-scan.ts`, ADR 0100), under the
 * same policy and as findings of the same image digest. Writes `<stem>.web-app.json` and `<stem>.web-app.policy.json`.
 * Returns whether the policy passed, or `undefined` when the image carries no web app and none is `required`.
 */
export async function scanWebApp(
	subject: Pick<Subject, "image" | "repository">,
	digest: string,
	platform: string,
	stem: string,
	options: { annotate: boolean; required: boolean },
): Promise<boolean | undefined> {
	const reference = `${subject.repository}@${digest}`;
	const scratch = await mkdtemp(path.join(tmpdir(), "web-app-"));
	try {
		const webApp = await extractWebApp(reference, platform, scratch);
		if (webApp === undefined) {
			if (options.required) {
				throw new Error(`${reference} carries no web app in ${RUNNER_JAR}`);
			}
			return undefined;
		}
		const report = path.resolve(`${stem}.${WEB_APP_STEM}.json`);
		await run(
			"trivy",
			webAppTrivyArguments(webApp, report, [
				"--skip-db-update",
				"--scanners",
				"vuln",
				"--list-all-pkgs",
			]),
			{ cwd: scratch },
		);
		assertKnownPackagesScanned(JSON.parse(await readFile(report, "utf8")));
		const result = `${stem}.${WEB_APP_STEM}.policy.json`;
		await rm(result, { force: true });
		const passed = await evaluatorPassed(
			[
				path.join(import.meta.dirname, "..", "check-release-vulnerabilities.ts"),
				subject.image,
				platform,
				digest,
				subject.repository,
				report,
				"security/vulnerability-policy.json",
				result,
				"--web-app",
			],
			options.annotate,
		);
		if (!passed && !existsSync(result)) {
			throw new Error(
				`vulnerability policy evaluation produced no result for the web app of ${reference}`,
			);
		}
		return passed;
	} finally {
		await rm(scratch, { recursive: true, force: true });
	}
}

export async function scanAll(
	subjects: readonly Subject[],
	directory: string,
	options: ScanOptions = {},
): Promise<ScanOutcome[]> {
	await mkdir(directory, { recursive: true });
	const platform = options.platform ?? PLATFORM;
	const outcomes: ScanOutcome[] = [];
	for (const subject of subjects) {
		try {
			outcomes.push(
				await scan(
					subject,
					directory,
					platform,
					options.annotate ?? false,
					options.requireWebApp ?? false,
				),
			);
		} catch (error) {
			if (options.continueOnError !== true) {
				throw error;
			}
			const detail = error instanceof Error ? error.message : String(error);
			process.stderr.write(`${subject.image} (${platform}): ${detail}\n`);
			outcomes.push({ image: subject.image, platform, passed: false, error: detail });
		}
	}
	return outcomes;
}
