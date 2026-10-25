/**
 * The release's Helm chart as an OCI artifact: package it from the checked-out source, push it,
 * and verify a pulled copy against the signed release lock.
 *
 * The chart takes its version and appVersion from the release, so the release stays the one source
 * of versions. Its values name no image: the images come from the same signed lock, through
 * kubernetes-chart.ts, so the package never embeds the lock that later names its own digest.
 *
 * Helm addresses an OCI chart by manifest digest. A pull by that digest returns exactly the pushed
 * manifest, and the registry client checks the layer against it. The package bytes are also signed
 * as a blob with the release workflow's identity, the same keyless identity as the lock itself.
 */
import { createHash } from "node:crypto";
import { mkdtemp, readFile, readdir, rename, rm } from "node:fs/promises";
import path from "node:path";

import { parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { releaseCertificateIdentity, releaseIdentityFor } from "./lib/release-identities.ts";
import {
	chartVersion,
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
): Promise<string> {
	if (chart.repository !== chartRepository(release)) {
		throw new Error(`the lock names chart ${chart.repository}, not ${chartRepository(release)}`);
	}
	await run("helm", [
		"pull",
		`oci://${chart.repository}@${chart.digest}`,
		"--destination",
		destination,
	]);
	const pulled = await readdir(destination);
	const expected = chartPackageName(release);
	if (pulled.length !== 1 || pulled[0] !== expected) {
		throw new Error(
			`pulling the chart produced ${pulled.join(", ") || "nothing"}, not ${expected}`,
		);
	}
	return path.join(destination, expected);
}

/**
 * Verify the chart that a schema 2 lock names: the package signature from the release workflow, the
 * pulled digest, and the chart metadata. The caller verified the lock's own signature first.
 * Returns the verified package, moved into `destination` only after every check passed.
 */
export async function verifyReleaseChart(
	lock: ReleaseImageLockV2,
	signature: string,
	destination: string,
	environment: NodeJS.ProcessEnv,
	run: Execute = execute,
): Promise<string> {
	const scratch = await mkdtemp(path.join(destination, ".chart-"));
	try {
		const pulled = await pullChart(lock.chart, lock.release, scratch, run);
		await run("cosign", [
			"verify-blob",
			"--bundle",
			signature,
			"--certificate-identity",
			releaseCertificateIdentity(lock.release, environment),
			"--certificate-oidc-issuer",
			"https://token.actions.githubusercontent.com",
			pulled,
		]);
		await inspectChart(pulled, lock.release, run);
		const verified = path.join(destination, chartPackageName(lock.release));
		await rename(pulled, verified);
		return verified;
	} finally {
		await rm(scratch, { recursive: true, force: true });
	}
}

/**
 * Package the chart source at the release version, push it, and pull it back by the digest the
 * push reported. The round trip must return the packaged bytes, or the digest is refused.
 */
export async function publishReleaseChart(
	release: string,
	source: string,
	destination: string,
	run: Execute = execute,
): Promise<{ chart: ReleaseChart; package: string }> {
	const version = chartVersion(release);
	await run("helm", [
		"package",
		source,
		"--version",
		version,
		"--app-version",
		version,
		"--destination",
		destination,
	]);
	const packaged = path.join(destination, chartPackageName(release));
	await inspectChart(packaged, release, run);
	const repository = chartRepository(release);
	const pushed = await run("helm", [
		"push",
		packaged,
		`oci://${repository.slice(0, -"/hephaestus".length)}`,
	]);
	const digest = digestLine.exec(pushed)?.groups?.digest;
	if (digest === undefined) {
		throw new Error("helm push reported no chart digest");
	}
	const chart = { repository, version, digest };
	const scratch = await mkdtemp(path.join(destination, ".roundtrip-"));
	try {
		const pulled = await pullChart(chart, release, scratch, run);
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

if (import.meta.main) {
	const [mode = "", ...args] = process.argv.slice(2);
	if (mode === "publish" && args.length === 3) {
		const [release = "", source = "", destination = ""] = args;
		const { chart } = await publishReleaseChart(release, source, destination);
		process.stdout.write(`${JSON.stringify(chart)}\n`);
	} else if (mode === "verify" && args.length === 4) {
		const [lockPath = "", release = "", signature = "", destination = ""] = args;
		const verified = await verifyReleaseChart(
			await readChartLock(lockPath, release),
			signature,
			destination,
			process.env,
		);
		process.stdout.write(`${verified}\n`);
	} else {
		throw new Error(
			"usage: release-chart publish <release> <chart-source> <directory> | verify <lock.json> <release> <package-signature> <directory>",
		);
	}
}
