import { existsSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { scanAll, type ScanOutcome } from "./lib/image-scan.ts";
import { readJsonFileSync } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { releaseCertificateIdentity, releaseIdentityFor } from "./lib/release-identities.ts";
import {
	isRelease,
	parseReleaseImageLock,
	verifyLockAgainstEvidence,
} from "./release-image-lock.ts";
import { requiresSignedEvidence, validateManifest } from "./verify-release-evidence.ts";

/** Authenticate the complete subject set before any archived judgment or fresh registry scan. */
async function authenticateSubjects(directory: string, release: string) {
	if (!isRelease(release)) {
		throw new Error("malformed supported release");
	}
	const lockPath = path.join(directory, `release-${release}.json`);
	await run("cosign", [
		"verify-blob",
		"--bundle",
		`${lockPath}.sigstore.json`,
		"--certificate-identity",
		releaseCertificateIdentity(release, process.env),
		"--certificate-oidc-issuer",
		"https://token.actions.githubusercontent.com",
		lockPath,
	]);
	const lock = parseReleaseImageLock(readJsonFileSync(lockPath), release);
	const manifestValue = readJsonFileSync(path.join(directory, "manifest.json"));
	verifyLockAgainstEvidence(lock, manifestValue);
	return validateManifest(
		manifestValue,
		readJsonFileSync(path.join(directory, "release-images.json")),
		releaseIdentityFor(release).namespace,
	);
}

export async function rescanReleaseImages(directory: string, reports: string, release: string) {
	await mkdir(reports, { recursive: true });
	let manifest;
	try {
		manifest = await authenticateSubjects(directory, release);
	} catch (error) {
		await writeFile(
			path.join(reports, "release-rescan.json"),
			`${JSON.stringify(
				{
					release,
					status: "fail",
					authentication: {
						status: "fail",
						error: error instanceof Error ? error.message : String(error),
					},
					outcomes: [],
				},
				null,
				2,
			)}\n`,
		);
		throw error;
	}
	let archiveAssurance:
		| { status: "pass" }
		| { status: "fail"; error: string }
		| { status: "not_authenticated"; reason: string };
	if (
		!requiresSignedEvidence(release) &&
		!existsSync(path.join(directory, "SHA256SUMS.sigstore.json"))
	) {
		const reason =
			"This historical release predates signed evidence checksums; archived policy, native identity and advisory bytes were not evaluated.";
		process.stderr.write(`::warning::${reason}\n`);
		archiveAssurance = { status: "not_authenticated", reason };
	} else {
		try {
			await output("node", [
				path.join(import.meta.dirname, "verify-release-evidence.ts"),
				directory,
			]);
			archiveAssurance = { status: "pass" };
		} catch (error) {
			const detail = error instanceof Error ? error.message : String(error);
			process.stderr.write(`${detail}\n`);
			archiveAssurance = { status: "fail", error: detail };
		}
	}
	const outcomes: ScanOutcome[] = [];
	for (const platform of ["linux/amd64", "linux/arm64"] as const) {
		const subjects = manifest.subjects
			.filter((subject) => subject.platform === platform)
			.map((subject) => ({
				image: subject.image,
				digest: subject.digest,
				repository: subject.repository,
				reference: `${subject.repository}@${subject.digest}`,
			}));
		outcomes.push(
			...(await scanAll(subjects, reports, { platform, annotate: true, continueOnError: true })),
		);
	}
	const currentSecurity = { status: outcomes.every((outcome) => outcome.passed) ? "pass" : "fail" };
	const result = {
		release,
		scannedAt: new Date().toISOString(),
		authentication: { status: "pass" },
		archiveAssurance,
		currentSecurity,
		subjects: manifest.subjects,
		outcomes,
		status:
			archiveAssurance.status !== "fail" && currentSecurity.status === "pass" ? "pass" : "fail",
	};
	await writeFile(
		path.join(reports, "release-rescan.json"),
		`${JSON.stringify(result, null, 2)}\n`,
	);
	return result;
}

if (import.meta.main) {
	const [directory, reports, release] = process.argv.slice(2);
	if (!isSet(directory) || !isSet(reports) || !isSet(release)) {
		throw new Error("usage: rescan-release-images <release-evidence> <reports> <release>");
	}
	const result = await rescanReleaseImages(directory, reports, release);
	if (result.status === "fail") {
		process.stderr.write(
			"::error::supported release evidence or fresh scans failed; inspect diagnostic reports\n",
		);
		process.exitCode = 1;
	}
}
