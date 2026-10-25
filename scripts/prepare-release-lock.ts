import { mkdtemp, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { run } from "./lib/process.ts";
import { releaseCertificateIdentity, releaseRepository } from "./lib/release-identities.ts";
import { isRelease } from "./release-image-lock.ts";

/** A stalled download must not hold a host's reconcile tick until its unit gives up. */
const DOWNLOAD_TIMEOUT_MS = 5 * 60_000;

const repositoryRoot = path.join(import.meta.dirname, "..");

export interface PrepareOptions {
	release: string;
	/** The Compose environment file. */
	output: string;
}

export interface PrepareDependencies {
	environment: NodeJS.ProcessEnv;
	download: (repository: string, release: string, names: string[], into: string) => Promise<void>;
	run: (command: string, args: string[]) => Promise<void>;
}

async function downloadReleaseAsset(
	repository: string,
	tag: string,
	name: string,
	into: string,
): Promise<void> {
	const url = `https://github.com/${repository}/releases/download/${tag}/${name}`;
	const response = await fetch(url, { signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MS) });
	if (!response.ok) {
		throw new Error(`GET ${url} returned ${response.status}`);
	}
	await writeFile(path.join(into, name), Buffer.from(await response.arrayBuffer()));
}

const defaults: PrepareDependencies = {
	environment: process.env,
	download: async (repository, release, names, into) => {
		if (process.env.GITHUB_ACTIONS === "true") {
			if (!isSet(process.env.GH_TOKEN)) {
				throw new Error("GH_TOKEN is required to verify a draft release");
			}
			// Draft release assets used by the publication smoke test require authenticated GitHub access.
			await run("gh", [
				"release",
				"download",
				release,
				"--repo",
				repository,
				"--dir",
				into,
				...names.flatMap((name) => ["--pattern", name]),
			]);
			return;
		}
		await Promise.all(
			names.map(async (name) => downloadReleaseAsset(repository, release, name, into)),
		);
	},
	run,
};

/**
 * Verify a release and write the Compose environment that a host deploys from it. The lock
 * signature is checked first, then the lock against the evidence manifest. Nothing reaches
 * `output` before both checks passed. A Kubernetes installation verifies the signed chart itself
 * and needs none of this (docs/admin/kubernetes.mdx).
 */
export async function prepareReleaseLock(
	{ release, output }: PrepareOptions,
	dependencies: PrepareDependencies = defaults,
): Promise<void> {
	if (!isRelease(release)) {
		throw new Error("usage: prepare-release-lock vX.Y.Z [output]");
	}
	const { environment } = dependencies;
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-release-"));
	const outputDirectory = path.dirname(output);
	const outputTempDirectory = await mkdtemp(
		path.join(outputDirectory, `.${path.basename(output)}-`),
	);
	const temporaryOutput = path.join(outputTempDirectory, "lock.env");
	const asset = `release-${release}.json`;
	const repository = releaseRepository(release, environment);
	try {
		await dependencies.download(
			repository,
			release,
			[asset, `${asset}.sigstore.json`, "manifest.json"],
			directory,
		);
		await dependencies.run("cosign", [
			"verify-blob",
			"--bundle",
			path.join(directory, `${asset}.sigstore.json`),
			"--certificate-identity",
			// Locks signed before the repository transfer carry the old owner/repo in their
			// certificate, so the expected identity is the release's, not the run's.
			releaseCertificateIdentity(release, environment),
			"--certificate-oidc-issuer",
			"https://token.actions.githubusercontent.com",
			path.join(directory, asset),
		]);
		await dependencies.run(process.execPath, [
			path.join(import.meta.dirname, "release-image-lock.ts"),
			path.join(directory, asset),
			path.join(directory, "manifest.json"),
			release,
			temporaryOutput,
		]);
		await rename(temporaryOutput, output);
		console.log(`Verified ${release}; wrote ${output}`);
	} finally {
		await rm(directory, { recursive: true, force: true });
		await rm(outputTempDirectory, { recursive: true, force: true });
	}
}

if (import.meta.main) {
	const [release = "", output = path.join(repositoryRoot, "docker/self-host/release-lock.env")] =
		process.argv.slice(2);
	await prepareReleaseLock({ release, output });
}
