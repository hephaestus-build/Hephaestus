import { mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";

import { readInventory } from "./commit-image-lock.ts";
import { chartValues, renderChartValues } from "./kubernetes-chart.ts";
import { parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { downloadReleaseAssets, type ReleaseAssetSource } from "./lib/release-assets.ts";
import { releaseCertificateIdentity, releaseRepository } from "./lib/release-identities.ts";
import { verifyReleaseChart, type Execute } from "./release-chart.ts";
import {
	isRelease,
	jointLockAsset,
	parseReleaseImageLock,
	verifyLockAgainstEvidence,
} from "./release-image-lock.ts";

const defaults: ReleaseAssetSource = { environment: process.env, download: downloadReleaseAssets };

/** Runs from the installed tooling, before any code from the candidate release can run. */
export async function prepareReleaseChart(
	release: string,
	commit: string,
	destination: string,
	helm: string,
	inventoryFile: string,
	dependencies: ReleaseAssetSource = defaults,
	execute: Execute = async (command, args) =>
		output(command === "helm" ? helm : command, args, { signal: AbortSignal.timeout(120_000) }),
): Promise<string> {
	if (!isRelease(release)) {
		throw new Error("expected a release tag");
	}
	const scratch = await mkdtemp(path.join(destination, ".release-"));
	try {
		const asset = jointLockAsset(release);
		await dependencies.download(
			releaseRepository(release, dependencies.environment),
			release,
			[asset, `${asset}.sigstore.json`, "manifest.json"],
			scratch,
		);
		await execute("cosign", [
			"verify-blob",
			"--bundle",
			path.join(scratch, `${asset}.sigstore.json`),
			"--certificate-identity",
			releaseCertificateIdentity(release, dependencies.environment),
			"--certificate-oidc-issuer",
			"https://token.actions.githubusercontent.com",
			path.join(scratch, asset),
		]);
		const lock = parseReleaseImageLock(
			parseJson(await readFile(path.join(scratch, asset), "utf8")),
			release,
		);
		if (lock.schemaVersion !== 2 || lock.commit !== commit) {
			throw new Error("signed chart lock does not cover the release source tree");
		}
		verifyLockAgainstEvidence(
			lock,
			parseJson(await readFile(path.join(scratch, "manifest.json"), "utf8")),
		);
		const inventory = await readInventory(inventoryFile);
		const packaged = await verifyReleaseChart(
			lock,
			inventory,
			destination,
			dependencies.environment,
			execute,
		);
		const temporary = path.join(scratch, "images.yaml");
		await writeFile(temporary, renderChartValues(chartValues(lock, inventory)));
		await rename(temporary, path.join(destination, `${release}.images.yaml`));
		return packaged;
	} finally {
		await rm(scratch, { recursive: true, force: true });
	}
}

if (import.meta.main) {
	const [release = "", commit = "", destination = "", helm = "helm", inventory = ""] =
		process.argv.slice(2);
	await prepareReleaseChart(release, commit, destination, helm, inventory);
}
