/**
 * Release publication and preflight share this generator so they verify the same evidence contract.
 * docs/contributor/release-management.mdx owns the bundle and its release-only signature checks.
 */
import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { isImageIndex, selectPlatformDigest } from "./lib/image-scan.ts";
import { asRecord, asString, readJsonFile } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { parseResolvedImages, type ResolvedImage } from "./resolve-release-images.ts";
import { planUpstreamSubjects } from "./scan-upstream-images.ts";
import type { Subject } from "./verify-release-evidence.ts";

/**
 * The platforms every release image is evidenced on, in the order the manifest must list them.
 * `validateManifest` rejects any other order, so the generator and the gate share this constant
 * rather than each spelling the pair out.
 */
export const PLATFORMS = ["linux/amd64", "linux/arm64"] as const;

export type Platform = (typeof PLATFORMS)[number];

/** One image the release ships, resolved to the multi-architecture index that carries it. */
export interface EvidenceImage {
	readonly image: string;
	readonly indexDigest: string;
	readonly provenance: Subject["provenance"];
	readonly repository: string;
}

export interface ManifestMetadata {
	readonly commit: string;
	readonly durationSeconds: number;
	readonly generatedAt: string;
	readonly release: string;
}

export interface EvidenceManifest extends ManifestMetadata {
	readonly schemaVersion: 1;
	readonly subjects: readonly Subject[];
}

const DIGEST = /^sha256:[a-f0-9]{64}$/;

/**
 * Every image the bundle must cover: the first-party half as the resolver read it out of the
 * registry, then the upstream half as `security/release-images.json` pins it. Both halves come from
 * the planners the pre-release scans already use, so no third definition of "what the release ships"
 * exists to drift.
 */
export function planEvidenceImages(
	firstParty: readonly ResolvedImage[],
	inventory: unknown,
): EvidenceImage[] {
	return [
		...firstParty.map((image) => ({ ...image, provenance: "first-party" as const })),
		...planUpstreamSubjects(inventory).map(({ image, indexDigest, repository }) => ({
			image,
			indexDigest,
			provenance: "upstream" as const,
			repository,
		})),
	];
}

/** Each image expanded into its per-platform subjects, in the manifest's canonical order. */
export function planEvidenceSubjects(
	images: readonly EvidenceImage[],
	platformDigest: (image: EvidenceImage, platform: Platform) => string,
): Subject[] {
	return images.flatMap((image) =>
		PLATFORMS.map((platform) => {
			const digest = platformDigest(image, platform);
			if (!DIGEST.test(digest))
				throw new Error(`${image.image} ${platform} digest is malformed: ${digest || "<empty>"}`);
			return {
				digest,
				image: image.image,
				indexDigest: image.indexDigest,
				platform,
				provenance: image.provenance,
				repository: image.repository,
			};
		}),
	);
}

/**
 * The manifest as it is written to disk, signed over and published as a release asset. The key order
 * is the published one — what the document names, then when it was made, then what it covers — and
 * is kept deliberately, because a released manifest is read by people as well as by the verifier.
 */
export function buildManifest(
	subjects: readonly Subject[],
	metadata: ManifestMetadata,
): EvidenceManifest {
	return {
		schemaVersion: 1,
		release: metadata.release,
		commit: metadata.commit,
		generatedAt: metadata.generatedAt,
		durationSeconds: metadata.durationSeconds,
		subjects,
	};
}

/** `webapp-linux-amd64`, the stem every evidence document for one subject is named by. */
export function evidenceStem(subject: Pick<Subject, "image" | "platform">): string {
	return `${subject.image}-${subject.platform.replaceAll("/", "-")}`;
}

async function resolvePlatformDigests(
	images: readonly EvidenceImage[],
): Promise<Map<string, string>> {
	const digests = new Map<string, string>();
	for (const image of images) {
		const reference = `${image.repository}@${image.indexDigest}`;
		const index: unknown = JSON.parse(
			await output("docker", ["buildx", "imagetools", "inspect", reference, "--raw"]),
		);
		// Every release subject is a multi-platform index. `image-scan.ts` tolerates a single manifest
		// because it is handed references that may be one; here a single manifest means the image was
		// not built for both architectures, which blocks a release rather than licensing a fallback to
		// the index digest — Trivy resolves such a reference against the host it runs on, so the scan
		// would quietly be `linux/amd64` while being filed as this platform's evidence.
		if (!isImageIndex(index))
			throw new Error(`${reference} is a single manifest, not a multi-platform index`);
		for (const platform of PLATFORMS) {
			const digest = selectPlatformDigest(index, platform);
			if (digest === undefined) throw new Error(`${reference} publishes no ${platform} manifest`);
			digests.set(`${image.image}\0${platform}`, digest);
		}
	}
	return digests;
}

export async function captureSubjects(
	subjects: readonly Subject[],
	capture: (subject: Subject) => Promise<void>,
): Promise<void> {
	for (let offset = 0; offset < subjects.length; offset += 2) {
		const results = await Promise.allSettled(subjects.slice(offset, offset + 2).map(capture));
		const failures = results.filter((result) => result.status === "rejected");
		if (failures.length > 0) {
			throw new AggregateError(
				failures.map((result): unknown => result.reason),
				"Release evidence capture failed",
			);
		}
	}
}

export async function captureSubject(subject: Subject, directory: string): Promise<void> {
	const reference = `${subject.repository}@${subject.digest}`;
	const stem = path.join(directory, evidenceStem(subject));
	const timings: { tool: string; durationSeconds: number; success: boolean }[] = [];
	const scan = async (tool: string, args: string[]): Promise<void> => {
		const startedAt = performance.now();
		let success = false;
		try {
			await run(tool, args);
			success = true;
		} finally {
			const durationSeconds = Math.round((performance.now() - startedAt) / 10) / 100;
			timings.push({ tool, durationSeconds, success });
			console.info(
				`${evidenceStem(subject)}: ${tool} ${success ? "completed" : "failed"} in ${durationSeconds}s`,
			);
		}
	};
	try {
		// Scan the registry artefact, never a daemon copy of it: a daemon pull re-serializes an OCI
		// manifest as Docker schema 2, so the SBOM would record a locally computed manifestDigest instead
		// of the released one. `--platform` makes Syft fail loudly if the digest is not this platform's.
		await scan("syft", [
			"--from",
			"registry",
			reference,
			"--platform",
			subject.platform,
			"--scope",
			"squashed",
			"-o",
			`syft-json=${stem}.syft.json`,
			"-o",
			`spdx-json=${stem}.spdx.json`,
			"-o",
			`cyclonedx-json=${stem}.cdx.json`,
		]);
		await scan("trivy", [
			"image",
			"--skip-db-update",
			"--skip-java-db-update",
			"--cache-backend",
			"memory",
			"--scanners",
			"vuln,license",
			"--format",
			"json",
			"--output",
			`${stem}.trivy.json`,
			reference,
		]);
		// Both evidence consumers receive the same digest-bound scan, in the bundle's report paths.
		await copyFile(`${stem}.trivy.json`, `${stem}.license.json`);
	} finally {
		try {
			await writeFile(`${stem}.timings.json`, `${JSON.stringify({ subject, timings }, null, 2)}\n`);
		} catch (error) {
			console.warn(
				`Could not save advisory timings for ${evidenceStem(subject)}: ${String(error)}`,
			);
		}
	}
}

/**
 * The scanner builds the bundle was produced by, recorded so a finding can be attributed to a
 * scanner version rather than argued about.
 */
export async function toolVersions(): Promise<Record<string, string>> {
	const read = async (command: string, args: string[], field: string): Promise<string> =>
		asString(asRecord(JSON.parse(await output(command, args)), `${command} version`)[field], field);
	return {
		syft: await read("syft", ["version", "-o", "json"], "version"),
		trivy: await read("trivy", ["version", "--format", "json"], "Version"),
		cosign: await read("cosign", ["version", "--json"], "gitVersion"),
	};
}

export async function generateReleaseEvidence(options: {
	commit: string;
	digestsPath: string;
	directory: string;
	release: string;
}): Promise<EvidenceManifest> {
	const startedAt = Date.now();
	const { commit, digestsPath, directory, release } = options;
	await mkdir(directory, { recursive: true });
	await writeFile(
		path.join(directory, "tool-versions.json"),
		`${JSON.stringify(await toolVersions(), null, 2)}\n`,
	);
	const inventoryPath = "security/release-images.json";
	const images = planEvidenceImages(
		parseResolvedImages(await readFile(digestsPath, "utf8")),
		await readJsonFile(inventoryPath),
	);
	const digests = await resolvePlatformDigests(images);
	const subjects = planEvidenceSubjects(
		images,
		(image, platform) => digests.get(`${image.image}\0${platform}`) ?? "",
	);
	await captureSubjects(subjects, (subject) => captureSubject(subject, directory));
	const manifest = buildManifest(subjects, {
		commit,
		durationSeconds: Math.round((Date.now() - startedAt) / 1000),
		generatedAt: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
		release,
	});
	await writeFile(path.join(directory, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
	// The policy and the inventory the bundle was judged against travel with it: an evidence bundle
	// that cannot be re-evaluated is a claim, not evidence.
	await copyFile(
		"security/vulnerability-policy.json",
		path.join(directory, "vulnerability-policy.json"),
	);
	await copyFile(inventoryPath, path.join(directory, "release-images.json"));
	return manifest;
}

if (import.meta.main) {
	const [directory, ...rest] = process.argv.slice(2);
	const flags = new Map<string, string>();
	for (let index = 0; index < rest.length; index += 2) {
		const flag = rest[index];
		const value = rest[index + 1];
		if (flag === undefined || value === undefined || !flag.startsWith("--"))
			throw new Error(
				"usage: generate-release-evidence <directory> --release <tag> --commit <sha> --digests <tsv>",
			);
		flags.set(flag.slice(2), value);
	}
	const release = flags.get("release");
	const commit = flags.get("commit");
	const digestsPath = flags.get("digests");
	if (!directory || !release || !commit || !digestsPath)
		throw new Error(
			"usage: generate-release-evidence <directory> --release <tag> --commit <sha> --digests <tsv>",
		);
	const manifest = await generateReleaseEvidence({ commit, digestsPath, directory, release });
	process.stdout.write(
		`Evidenced ${manifest.subjects.length} subjects for ${release} in ${manifest.durationSeconds}s\n`,
	);
}
