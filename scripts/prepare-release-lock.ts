import { mkdtemp, rename, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { run } from "./lib/process.ts";
import { downloadReleaseAssets, type ReleaseAssetSource } from "./lib/release-assets.ts";
import { releaseCertificateIdentity, releaseRepository } from "./lib/release-identities.ts";
import { isRelease } from "./release-image-lock.ts";

const repositoryRoot = path.join(import.meta.dirname, "..");

export interface PrepareOptions {
	release: string;
	/** The Compose environment file. */
	output: string;
}

export interface PrepareDependencies extends ReleaseAssetSource {
	run: (command: string, args: string[]) => Promise<void>;
}

const defaults: PrepareDependencies = {
	environment: process.env,
	download: downloadReleaseAssets,
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
