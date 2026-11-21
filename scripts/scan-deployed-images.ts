/**
 * Rescans the images that the project's hosts run, as the signed deploy channels name them.
 *
 * The other rescans read `:main` and the latest stable release. Neither is what runs: staging can be
 * frozen on an older build, and production can run a release candidate. Each channel on the
 * `deploy-state` branch is verified against the `promote.yml` signer before anything reads it, exactly
 * as a host verifies it before it applies it.
 *
 * A channel that names images (a commit channel) is scanned here. A channel that names a release is
 * handed back to the workflow as `<channel>-release`, which `rescan-release-images.ts` authenticates
 * against that release's signed lock and scans.
 */
import { appendFile, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { type ScanOutcome, scanAll, type Subject } from "./lib/image-scan.ts";
import { asRecord, asString, parseJson, readJsonFile } from "./lib/json.ts";
import { run } from "./lib/process.ts";
import { PLATFORMS, planUpstreamSubjects } from "./scan-upstream-images.ts";

export const CHANNELS = ["staging", "production"] as const;
export type Channel = (typeof CHANNELS)[number];

const DIGEST_REFERENCE =
	/^(?<repository>[a-z0-9.-]+(?::\d+)?(?:\/[a-z0-9._-]+)+)@sha256:[a-f0-9]{64}$/u;
const RELEASE = /^v\d+\.\d+\.\d+(?:-rc\.\d+)?$/u;

/** The workflow identity that signs a channel; a host trusts no other (`deploy/reconcile/reconcile.env.example`). */
export function promoteIdentity(repository: string): string {
	return `https://github.com/${repository}/.github/workflows/promote.yml@refs/heads/main`;
}

export type ChannelTarget =
	| { readonly kind: "release"; readonly release: string }
	| { readonly kind: "commit"; readonly commit: string; readonly images: readonly string[] };

/** What a channel tells a host to run. */
export function channelTarget(value: unknown, channel: Channel): ChannelTarget {
	const document = asRecord(value, `${channel} channel`);
	if (document.release !== undefined) {
		const release = asString(document.release, `${channel} channel release`);
		if (!RELEASE.test(release)) {
			throw new Error(`${channel} channel names a malformed release: ${release}`);
		}
		return { kind: "release", release };
	}
	const commit = asString(document.commit, `${channel} channel commit`);
	if (!/^[a-f0-9]{40}$/u.test(commit)) {
		throw new Error(`${channel} channel names a malformed commit: ${commit}`);
	}
	const images = Object.values(asRecord(document.images, `${channel} channel images`)).map(
		(image, index) => asString(image, `${channel} channel images[${index}]`),
	);
	if (images.length === 0) {
		throw new Error(`${channel} channel names no images`);
	}
	return { kind: "commit", commit, images };
}

/**
 * Each deployed image as a scan subject, named as the policy's exceptions name it. A first-party image is
 * named by its repository under the registry, also after `security/release-images.json` stops listing it:
 * a host can still run an image that main no longer builds. An upstream image is named as the inventory
 * names it. An upstream image that the inventory does not name has no policy key, so it fails here instead
 * of being scanned under a guessed name.
 */
export function deployedSubjects(
	images: readonly string[],
	inventory: unknown,
	registry: string,
): Subject[] {
	const upstream = new Map(
		planUpstreamSubjects(inventory).map((subject) => [subject.repository, subject.image]),
	);
	return images.map((reference) => {
		const repository = DIGEST_REFERENCE.exec(reference)?.groups?.repository;
		if (repository === undefined) {
			throw new Error(`a deployed image is not pinned by digest: ${reference}`);
		}
		const owned = repository.startsWith(`${registry}/`)
			? repository.slice(registry.length + 1)
			: undefined;
		const image = owned ?? upstream.get(repository);
		if (image === undefined) {
			throw new Error(
				`security/release-images.json does not name the deployed image ${repository}`,
			);
		}
		return { image, reference, repository };
	});
}

async function verifiedChannel(directory: string, channel: Channel, repository: string) {
	const file = path.join(directory, "channels", `${channel}.json`);
	await run("cosign", [
		"verify-blob",
		"--bundle",
		`${file}.sigstore.json`,
		"--certificate-identity",
		promoteIdentity(repository),
		"--certificate-oidc-issuer",
		"https://token.actions.githubusercontent.com",
		file,
	]);
	return channelTarget(parseJson(await readFile(file, "utf8")), channel);
}

if (import.meta.main) {
	const [directory, reports] = process.argv.slice(2);
	if (!isSet(directory) || !isSet(reports)) {
		throw new Error("usage: scan-deployed-images <deploy-state checkout> <reports>");
	}
	const repository = asString(process.env.GITHUB_REPOSITORY, "GITHUB_REPOSITORY");
	const registry = process.env.IMAGE_REGISTRY ?? "ghcr.io/hephaestus-build";
	const inventory = await readJsonFile("security/release-images.json");
	const outcomes: ScanOutcome[] = [];
	const targets: Record<string, ChannelTarget> = {};
	for (const channel of CHANNELS) {
		const target = await verifiedChannel(directory, channel, repository);
		targets[channel] = target;
		if (target.kind === "release") {
			const githubOutput = process.env.GITHUB_OUTPUT;
			if (isSet(githubOutput)) {
				await appendFile(githubOutput, `${channel}-release=${target.release}\n`);
			}
			continue;
		}
		const subjects = deployedSubjects(target.images, inventory, registry);
		for (const platform of PLATFORMS) {
			outcomes.push(
				...(await scanAll(subjects, path.join(reports, channel), {
					platform,
					continueOnError: true,
				})),
			);
		}
	}
	await writeFile(
		path.join(reports, "deployed.json"),
		`${JSON.stringify({ scannedAt: new Date().toISOString(), targets, outcomes }, null, 2)}\n`,
	);
	for (const outcome of outcomes) {
		process.stdout.write(
			`${outcome.image} (${outcome.platform}): ${outcome.error ?? (outcome.passed ? "pass" : "fail")}\n`,
		);
	}
	// A policy refusal is a finding for the exposure report. Only an unavailable scan fails the step.
	if (outcomes.some((outcome) => outcome.error !== undefined)) {
		process.exitCode = 1;
	}
}
