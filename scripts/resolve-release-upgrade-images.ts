import { spawnSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import process from "node:process";

import { currentReleaseIdentity, releaseIdentityFor } from "./lib/release-identities.ts";

import { isSet, requiredEnv } from "./lib/env.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

// The candidate is built by this repository's CI, so it lives in the current
// namespace. The previous release keeps the namespace it was published under —
// GHCR packages do not transfer between organizations (issue #1599) — so its
// repository is resolved per version from security/release-identities.json.
const currentNamespace = currentReleaseIdentity().namespace;
const applicationRepository = `${currentNamespace}/application-server`;
const postgresRepository = `${currentNamespace}/postgres`;

function command(executable: string, args: string[]): string {
	const result = spawnSync(executable, args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
	if (result.status !== 0) {
		throw new Error(`${executable} ${args.join(" ")} failed:\n${result.stdout}${result.stderr}`);
	}
	return result.stdout.trim();
}

function nonEmpty(value: string | undefined): string | undefined {
	return isSet(value) ? value : undefined;
}

function immutable(reference: string, repository: string): string {
	if (!reference.startsWith(`${repository}:`) && !reference.startsWith(`${repository}@sha256:`)) {
		throw new Error(`Unexpected image repository: ${reference}`);
	}
	const manifest = command("docker", [
		"buildx",
		"imagetools",
		"inspect",
		reference,
		"--format",
		"{{json .Manifest}}",
	]);
	const parsed: unknown = JSON.parse(manifest);
	if (
		typeof parsed !== "object" ||
		parsed === null ||
		!("digest" in parsed) ||
		typeof parsed.digest !== "string" ||
		!/^sha256:[a-f0-9]{64}$/u.test(parsed.digest)
	) {
		throw new Error(`Registry returned an invalid digest for ${reference}`);
	}
	return `${repository}@${parsed.digest}`;
}

function previousApplicationReference(version: string): {
	reference: string;
	repository: string;
} {
	const repository = `${releaseIdentityFor(version).namespace}/application-server`;
	return { reference: `${repository}:${version}`, repository };
}

const supplied = [
	process.env.INPUT_PREVIOUS_VERSION,
	process.env.INPUT_CANDIDATE_APP,
	process.env.INPUT_POSTGRES,
];
if (supplied.some(Boolean) && !supplied.every(Boolean)) {
	throw new Error(
		"Reusable workflow callers must provide the previous version and both image references",
	);
}

let previousApplication: { reference: string; repository: string };
const suppliedPreviousVersion = nonEmpty(supplied[0]);
let candidateApplication = nonEmpty(supplied[1]);
let postgres = nonEmpty(supplied[2]);
if (
	suppliedPreviousVersion !== undefined &&
	candidateApplication !== undefined &&
	postgres !== undefined
) {
	if (!/^[0-9]+\.[0-9]+\.[0-9]+$/u.test(suppliedPreviousVersion)) {
		throw new Error("Previous version must be a stable X.Y.Z version");
	}
	previousApplication = previousApplicationReference(suppliedPreviousVersion);
} else {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const requestedPrevious = nonEmpty(process.env.REQUESTED_PREVIOUS);
	const previous =
		requestedPrevious ??
		command("gh", [
			"release",
			"view",
			"--repo",
			repository,
			"--json",
			"tagName",
			"--jq",
			".tagName",
		]);
	if (!/^v[0-9]+\.[0-9]+\.[0-9]+$/u.test(previous)) {
		throw new Error("Previous release must be a stable vX.Y.Z tag");
	}
	const candidate = nonEmpty(process.env.REQUESTED_CANDIDATE) ?? nonEmpty(process.env.GITHUB_SHA);
	if (candidate === undefined || !/^[a-f0-9]{40}$/u.test(candidate)) {
		throw new Error("Candidate must be a full commit SHA");
	}
	previousApplication = previousApplicationReference(previous.slice(1));
	candidateApplication = `${applicationRepository}:${candidate}`;
	postgres = `${postgresRepository}:${candidate}`;
}

const output = requiredEnv(process.env, "GITHUB_OUTPUT");
appendFileSync(
	output,
	[
		`previous-app=${immutable(previousApplication.reference, previousApplication.repository)}`,
		`candidate-app=${immutable(candidateApplication, applicationRepository)}`,
		`postgres=${immutable(postgres, postgresRepository)}`,
		"",
	].join("\n"),
);
