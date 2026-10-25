import { mkdir, mkdtemp, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { parseInventory } from "./commit-image-lock.ts";
import { chartValues, renderChartValues } from "./kubernetes-chart.ts";
import { isSet } from "./lib/env.ts";
import { parseJson } from "./lib/json.ts";
import { run } from "./lib/process.ts";
import { releaseCertificateIdentity, releaseRepository } from "./lib/release-identities.ts";
import {
	chartPackageName,
	type Execute,
	readChartLock,
	verifyReleaseChart,
} from "./release-chart.ts";
import { isRelease, jointLockAsset } from "./release-image-lock.ts";

/** A stalled download must not hold a host's reconcile tick until its unit gives up. */
const DOWNLOAD_TIMEOUT_MS = 5 * 60_000;

const repositoryRoot = path.join(import.meta.dirname, "..");

export interface PrepareOptions {
	release: string;
	/** The Compose environment file. */
	output: string;
	/** A new directory for the verified chart package and its values, or none for Compose only. */
	chart?: string;
}

export interface PrepareDependencies {
	environment: NodeJS.ProcessEnv;
	download: (repository: string, release: string, names: string[], into: string) => Promise<void>;
	run: (command: string, args: string[]) => Promise<void>;
	/** The helm and cosign calls of the chart verification. */
	execute?: Execute;
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
 * Verify a release and write what a host deploys from it. The lock signature is checked first,
 * then the lock against the evidence manifest, then (for a chart) the chart by its locked digest.
 * Nothing reaches `output` or `chart` before every requested check passed.
 */
export async function prepareReleaseLock(
	{ release, output, chart }: PrepareOptions,
	dependencies: PrepareDependencies = defaults,
): Promise<void> {
	if (!isRelease(release)) {
		throw new Error("usage: prepare-release-lock vX.Y.Z [output] [--chart directory]");
	}
	const { environment } = dependencies;
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-release-"));
	const outputDirectory = path.dirname(output);
	const outputTempDirectory = await mkdtemp(
		path.join(outputDirectory, `.${path.basename(output)}-`),
	);
	const temporaryOutput = path.join(outputTempDirectory, "lock.env");
	const chartTempDirectory =
		chart === undefined
			? undefined
			: await mkdtemp(path.join(path.dirname(chart), `.${path.basename(chart)}-`));
	// Compose reads the schema 1 lock it always read. The chart path reads only the signed joint
	// lock: a release without one has no chart, and the schema 1 lock is never a fallback.
	const asset = chart === undefined ? `release-${release}.json` : jointLockAsset(release);
	const signature = `${chartPackageName(release)}.sigstore.json`;
	const repository = releaseRepository(release, environment);
	try {
		const assets = [asset, `${asset}.sigstore.json`, "manifest.json"];
		await dependencies.download(
			repository,
			release,
			chart === undefined ? assets : [...assets, signature],
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
		if (chartTempDirectory !== undefined && chart !== undefined) {
			const lock = await readChartLock(path.join(directory, asset), release);
			await verifyReleaseChart(
				lock,
				path.join(directory, signature),
				chartTempDirectory,
				environment,
				dependencies.execute,
			);
			const inventory = parseInventory(
				parseJson(
					await readFile(path.join(repositoryRoot, "security/release-images.json"), "utf8"),
				),
			);
			await writeFile(
				path.join(chartTempDirectory, "release-values.yaml"),
				renderChartValues(chartValues(lock, inventory)),
				{ flag: "wx" },
			);
			// A path that already exists is never replaced: mkdir refuses it before anything moves.
			// The entries move into it one by one, because Windows refuses a rename onto a directory.
			await mkdir(chart);
			try {
				for (const entry of await readdir(chartTempDirectory)) {
					await rename(path.join(chartTempDirectory, entry), path.join(chart, entry));
				}
			} catch (error) {
				await rm(chart, { recursive: true, force: true });
				throw error;
			}
		}
		await rename(temporaryOutput, output);
		console.log(
			`Verified ${release}; wrote ${output}${chart === undefined ? "" : ` and ${chart}`}`,
		);
	} finally {
		await rm(directory, { recursive: true, force: true });
		await rm(outputTempDirectory, { recursive: true, force: true });
		if (chartTempDirectory !== undefined) {
			await rm(chartTempDirectory, { recursive: true, force: true });
		}
	}
}

if (import.meta.main) {
	const args = process.argv.slice(2);
	const flag = args.indexOf("--chart");
	const chart = flag === -1 ? undefined : args[flag + 1];
	if (flag !== -1 && !isSet(chart)) {
		throw new Error("usage: prepare-release-lock vX.Y.Z [output] [--chart directory]");
	}
	const positional = flag === -1 ? args : [...args.slice(0, flag), ...args.slice(flag + 2)];
	const [release = "", output = path.join(repositoryRoot, "docker/self-host/release-lock.env")] =
		positional;
	await prepareReleaseLock({ release, output, chart });
}
