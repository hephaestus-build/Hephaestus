/**
 * The release's Helm chart as an OCI artifact: package it from a copy of the checked-out source
 * with the release's images in its values, push it, and verify a pulled copy against the signed
 * release lock.
 *
 * The chart takes its version and appVersion from the release, so the release stays the one source
 * of versions. Its values name the release's images by digest, from the same lock object that the
 * schema 1 and joint locks come from (lockFromEvidence, then kubernetes-chart.ts). The images are
 * known before the package exists; the joint lock that names the package's digest comes after it.
 *
 * Helm addresses an OCI chart by manifest digest. A pull by that digest returns exactly the pushed
 * manifest, and the registry client checks the layer against it. The release signs that digest in
 * the registry with `cosign sign` and the release workflow's identity, the same keyless identity as
 * the locks, so an operator verifies the chart with `cosign verify` alone.
 */
import { createHash } from "node:crypto";
import { cp, mkdtemp, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";

import { type ImageInventory, readInventory } from "./commit-image-lock.ts";
import {
	type ChartValues,
	chartValues,
	releaseValuesFile,
	releaseValuesLines,
} from "./kubernetes-chart.ts";
import { parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { releaseCertificateIdentity, releaseIdentityFor } from "./lib/release-identities.ts";
import {
	chartVersion,
	lockFromEvidence,
	parseReleaseImageLock,
	type ReleaseChart,
	type ReleaseImageLockV2,
} from "./release-image-lock.ts";

/** One registry or signature operation; a stalled one fails the release step. */
const COMMAND_TIMEOUT_MS = 120_000;
const digestLine = /^Digest: (?<digest>sha256:[a-f0-9]{64})$/mu;

export type Execute = (command: string, args: string[]) => Promise<string>;

const execute: Execute = async (command, args) =>
	output(command, args, { signal: AbortSignal.timeout(COMMAND_TIMEOUT_MS) });

/** The OCI repository of a release's chart, in the namespace that release publishes images to. */
export function chartRepository(release: string): string {
	return `${releaseIdentityFor(release).namespace}/charts/hephaestus`;
}

export function chartPackageName(release: string): string {
	return `hephaestus-${chartVersion(release)}.tgz`;
}

const sha256 = async (file: string) =>
	createHash("sha256")
		.update(await readFile(file))
		.digest("hex");

/**
 * Refuse a package whose Chart.yaml is not this release's chart.
 *
 * `helm show chart` has no JSON output. It prints the loaded metadata re-marshalled with one
 * top-level key per line. Only four of those keys are read, each as one exact line; the expected
 * values are fixed or a validated release version, which YAML prints without quotes. A missing,
 * repeated or different line for any of the four is refused. No YAML parser is involved.
 */
export async function inspectChart(file: string, release: string, run: Execute = execute) {
	const version = chartVersion(release);
	const expected = new Map([
		["apiVersion", "v2"],
		["name", "hephaestus"],
		["version", version],
		["appVersion", version],
	]);
	const seen = new Set<string>();
	const shown = await run("helm", ["show", "chart", file]);
	for (const line of shown.split(/\r?\n/u)) {
		const key = /^(?<key>[A-Za-z]+):/u.exec(line)?.groups?.key;
		const value = key === undefined ? undefined : expected.get(key);
		if (key === undefined || value === undefined) {
			continue;
		}
		if (seen.has(key) || line !== `${key}: ${value}`) {
			throw new Error(`${path.basename(file)} is not the hephaestus chart ${version}`);
		}
		seen.add(key);
	}
	if (seen.size !== expected.size) {
		throw new Error(`${path.basename(file)} is not the hephaestus chart ${version}`);
	}
}

/** Pull the locked chart by digest into a directory of its own and return the package path. */
export async function pullChart(
	chart: ReleaseChart,
	release: string,
	destination: string,
	run: Execute = execute,
	repository = chartRepository(release),
): Promise<string> {
	if (chart.repository !== repository) {
		throw new Error(`the lock names chart ${chart.repository}, not ${repository}`);
	}
	await run("helm", [
		"pull",
		`oci://${chart.repository}@${chart.digest}`,
		"--destination",
		destination,
	]);
	const pulled = await readdir(destination);
	// Helm names an OCI digest download after the reference, not the chart's version.
	const expected = `hephaestus@${chart.digest.replace(":", "-")}.tgz`;
	if (pulled.length !== 1 || pulled[0] !== expected) {
		throw new Error(
			`pulling the chart produced ${pulled.join(", ") || "nothing"}, not ${expected}`,
		);
	}
	return path.join(destination, expected);
}

/**
 * Verify the chart that a schema 2 lock names: the registry signature of its digest from the
 * release workflow, the pulled digest, the chart metadata, and that its values pin exactly the
 * lock's images. The caller verified the lock's own signature first. Returns the verified package,
 * moved into `destination` only after every check.
 */
export async function verifyReleaseChart(
	lock: ReleaseImageLockV2,
	inventory: ImageInventory,
	destination: string,
	environment: NodeJS.ProcessEnv,
	run: Execute = execute,
): Promise<string> {
	const scratch = await mkdtemp(path.join(destination, ".chart-"));
	try {
		const pulled = await pullChart(lock.chart, lock.release, scratch, run);
		await run("cosign", [
			"verify",
			`${lock.chart.repository}@${lock.chart.digest}`,
			"--certificate-identity",
			releaseCertificateIdentity(lock.release, environment),
			"--certificate-oidc-issuer",
			"https://token.actions.githubusercontent.com",
		]);
		await inspectChart(pulled, lock.release, run);
		await inspectValues(pulled, chartValues(lock, inventory), run);
		const verified = path.join(destination, chartPackageName(lock.release));
		await rename(pulled, verified);
		return verified;
	} finally {
		await rm(scratch, { recursive: true, force: true });
	}
}

/** The top-level keys that the release lines own. */
const releaseKeys = /^(?:release|images):/gmu;

/**
 * Refuse a package whose values.yaml, as Helm reads it back, does not hold the release's lines
 * exactly once, or holds another top-level `release` or `images` key that could replace them.
 */
async function inspectValues(file: string, values: ChartValues, run: Execute) {
	const shown = await run("helm", ["show", "values", file]);
	const lines = releaseValuesLines(values);
	const at = shown.indexOf(lines);
	if (
		at === -1 ||
		(at > 0 && shown[at - 1] !== "\n") ||
		(shown.match(releaseKeys)?.length ?? 0) !== 2
	) {
		throw new Error(`${path.basename(file)} does not pin the ${values.release.version} images`);
	}
}

/**
 * Package a copy of the chart source at the release version, with the release's values written
 * into its values.yaml, and refuse a package that Helm does not read as this release's chart with
 * these values. The source itself is never changed. Returns the package path.
 */
export async function packageReleaseChart(
	release: string,
	source: string,
	values: ChartValues,
	destination: string,
	run: Execute = execute,
): Promise<string> {
	const version = chartVersion(release);
	if (values.release.version !== release) {
		throw new Error(`the chart values are for ${values.release.version}, not ${release}`);
	}
	const staging = await mkdtemp(path.join(destination, ".source-"));
	try {
		// Helm names the package from Chart.yaml; the copy keeps the chart's directory name too.
		const staged = path.join(staging, "hephaestus");
		await cp(source, staged, { recursive: true });
		const valuesFile = path.join(staged, "values.yaml");
		await writeFile(valuesFile, releaseValuesFile(await readFile(valuesFile, "utf8"), values));
		await run("helm", [
			"package",
			staged,
			"--version",
			version,
			"--app-version",
			version,
			"--destination",
			destination,
		]);
	} finally {
		await rm(staging, { recursive: true, force: true });
	}
	const packaged = path.join(destination, chartPackageName(release));
	await inspectChart(packaged, release, run);
	await inspectValues(packaged, values, run);
	return packaged;
}

/**
 * Package the release chart, push it, and pull it back by the digest the push reported. The round
 * trip must return the packaged bytes, or the digest is refused. Only the signing rehearsal passes
 * a repository other than the release's own.
 */
export async function publishReleaseChart(
	release: string,
	source: string,
	values: ChartValues,
	destination: string,
	run: Execute = execute,
	repository = chartRepository(release),
): Promise<{ chart: ReleaseChart; package: string }> {
	const packaged = await packageReleaseChart(release, source, values, destination, run);
	const pushed = await run("helm", [
		"push",
		packaged,
		`oci://${repository.slice(0, -"/hephaestus".length)}`,
	]);
	const digest = digestLine.exec(pushed)?.groups?.digest;
	if (digest === undefined) {
		throw new Error("helm push reported no chart digest");
	}
	const chart = { repository, version: chartVersion(release), digest };
	const scratch = await mkdtemp(path.join(destination, ".roundtrip-"));
	try {
		const pulled = await pullChart(chart, release, scratch, run, repository);
		if ((await sha256(pulled)) !== (await sha256(packaged))) {
			throw new Error(`the chart pulled by ${digest} differs from the package that was pushed`);
		}
	} finally {
		await rm(scratch, { recursive: true, force: true });
	}
	return { chart, package: packaged };
}

export async function readChartLock(file: string, release: string): Promise<ReleaseImageLockV2> {
	const lock = parseReleaseImageLock(parseJson(await readFile(file, "utf8")), release);
	if (lock.schemaVersion !== 2) {
		throw new Error(`the ${release} lock has no chart`);
	}
	return lock;
}

/**
 * The chart values of a release, from its verified evidence manifest and the release image
 * inventory of the checkout: the same lock object that the release's locks are written from.
 */
export async function releaseChartValues(
	manifest: string,
	inventory: string,
	release: string,
): Promise<ChartValues> {
	return chartValues(
		lockFromEvidence(parseJson(await readFile(manifest, "utf8")), release),
		await readInventory(inventory),
	);
}

if (import.meta.main) {
	const [mode = "", ...args] = process.argv.slice(2);
	if (mode === "publish" && args.length === 5) {
		const [release = "", source = "", manifest = "", inventory = "", destination = ""] = args;
		const { chart } = await publishReleaseChart(
			release,
			source,
			await releaseChartValues(manifest, inventory, release),
			destination,
		);
		process.stdout.write(`${JSON.stringify(chart)}\n`);
	} else if (mode === "verify" && args.length === 4) {
		const [lockPath = "", release = "", inventory = "", destination = ""] = args;
		const verified = await verifyReleaseChart(
			await readChartLock(lockPath, release),
			await readInventory(inventory),
			destination,
			process.env,
		);
		process.stdout.write(`${verified}\n`);
	} else {
		throw new Error(
			"usage: release-chart publish <release> <chart-source> <evidence-manifest> <inventory> <directory> | verify <lock.json> <release> <inventory> <directory>",
		);
	}
}
