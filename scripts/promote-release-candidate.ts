import { appendFile, mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import { isSet } from "./lib/env.ts";
import { parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { releaseCertificateIdentity, releaseRepository } from "./lib/release-identities.ts";
import { isCandidatePromotion } from "./lib/release-version.ts";
import { verifyCandidateSource } from "./plan-release.ts";
import {
	parseReleaseImageLock,
	type ReleaseImageLock,
	verifyLockAgainstEvidence,
} from "./release-image-lock.ts";
import { digestOutputs, formatResolvedImages } from "./resolve-release-images.ts";

/** Promotion preserves the qualified source and images while publishing stable chart metadata. */
export function assertCandidateArtifacts(
	source: ReleaseImageLock,
	promoted: ReleaseImageLock,
): void {
	if (
		source.schemaVersion !== 2 ||
		promoted.schemaVersion !== 2 ||
		!isCandidatePromotion(source.release, promoted.release)
	) {
		throw new Error("Stable promotion needs the joint lock of its same-core RC");
	}
	if (
		source.commit !== promoted.commit ||
		!isDeepStrictEqual(source.images, promoted.images) ||
		source.chart.repository !== promoted.chart.repository
	) {
		throw new Error("Promotion changed the candidate's source, image digests or chart repository");
	}
}

const execute = async (command: string, args: string[]) =>
	output(command, args, { signal: AbortSignal.timeout(120_000) });

export async function captureCandidate(
	candidate: string,
	sha: string,
	directory: string,
	imagesFile: string,
): Promise<void> {
	const environment = process.env;
	const repository = releaseRepository(candidate, environment);
	await verifyCandidateSource(repository, candidate, sha);
	await mkdir(directory, { recursive: true, mode: 0o700 });
	const joint = `release-${candidate}.chart.json`;
	await execute("gh", [
		"release",
		"download",
		candidate,
		"--repo",
		repository,
		"--dir",
		directory,
		"--pattern",
		joint,
		"--pattern",
		`${joint}.sigstore.json`,
		"--pattern",
		"manifest.json",
	]);
	await execute("cosign", [
		"verify-blob",
		"--bundle",
		path.join(directory, `${joint}.sigstore.json`),
		"--certificate-identity",
		releaseCertificateIdentity(candidate, environment),
		"--certificate-oidc-issuer",
		"https://token.actions.githubusercontent.com",
		path.join(directory, joint),
	]);
	const lock = parseReleaseImageLock(
		parseJson(await readFile(path.join(directory, joint), "utf8")),
		candidate,
	);
	if (lock.schemaVersion !== 2 || lock.commit !== sha) {
		throw new Error("The signed candidate lock has another source or no chart");
	}
	verifyLockAgainstEvidence(
		lock,
		parseJson(await readFile(path.join(directory, "manifest.json"), "utf8")),
	);
	await writeFile(imagesFile, formatResolvedImages(lock.images));
	if (isSet(environment.GITHUB_OUTPUT)) {
		await appendFile(environment.GITHUB_OUTPUT, digestOutputs(lock.images));
	}
}

if (import.meta.main) {
	const [mode, ...args] = process.argv.slice(2);
	if (mode === "capture" && args.length === 4) {
		const [candidate = "", sha = "", directory = "", imagesFile = ""] = args;
		await captureCandidate(candidate, sha, directory, imagesFile);
	} else if (mode === "verify" && args.length === 2) {
		const [source = "", promoted = ""] = args;
		assertCandidateArtifacts(
			parseReleaseImageLock(parseJson(await readFile(source, "utf8"))),
			parseReleaseImageLock(parseJson(await readFile(promoted, "utf8"))),
		);
	} else {
		throw new Error(
			"usage: promote-release-candidate capture <candidate> <sha> <directory> <images.tsv> | verify <source-lock> <stable-lock>",
		);
	}
}
