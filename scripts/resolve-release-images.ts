/**
 * Resolves the first-party release images a CI/CD run published to their index digests.
 *
 * Both release paths resolve through the commit tag the image build publishes, so a re-run of a
 * failed job resolves the artefact its own run produced.
 *
 * The image set itself is not redefined here: `planSubjects` derives it from
 * `security/release-images.json`, the same file the release evidence gate validates the finished
 * manifest against.
 */
import { appendFile, writeFile } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";

import { isSet } from "./lib/env.ts";
import type { Subject } from "./lib/image-scan.ts";
import { readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { planSubjects } from "./scan-main-images.ts";

const DIGEST = /^sha256:[a-f0-9]{64}$/u;

/** One first-party image and the multi-architecture index this run published for it. */
export interface ResolvedImage {
	readonly image: string;
	readonly indexDigest: string;
	readonly repository: string;
}

export interface ResolveOptions {
	/** Reads a reference's index digest; injected so the retry loop is testable without a registry. */
	readonly inspect?: (reference: string) => Promise<string>;
	/** Total attempts per image. The default spans two minutes at the default delay. */
	readonly attempts?: number;
	readonly delayMs?: number;
	readonly sleep?: (milliseconds: number) => Promise<void>;
}

async function inspectIndexDigest(reference: string): Promise<string> {
	return output("docker", [
		"buildx",
		"imagetools",
		"inspect",
		"--format",
		"{{.Manifest.Digest}}",
		reference,
	]);
}

/**
 * Every image's index digest, in inventory order.
 *
 * A manifest is not always readable the instant it is pushed, and a release that gave up on the
 * first read would be a flake rather than a finding, so each image is retried. An unreadable image
 * still fails the run: an incomplete subject set is exactly what the evidence gate exists to catch,
 * and it must never be reached by silently dropping one.
 */
export async function resolveReleaseImages(
	subjects: readonly Subject[],
	options: ResolveOptions = {},
): Promise<ResolvedImage[]> {
	const inspect = options.inspect ?? inspectIndexDigest;
	const attempts = options.attempts ?? 24;
	const delayMs = options.delayMs ?? 5000;
	const pause = options.sleep ?? sleep;
	const resolved: ResolvedImage[] = [];
	for (const subject of subjects) {
		let digest = "";
		for (let attempt = 1; attempt <= attempts; attempt += 1) {
			const inspected = await inspect(subject.reference).catch(() => "");
			digest = inspected.trim();
			if (DIGEST.test(digest)) {
				break;
			}
			if (attempt === attempts) {
				throw new Error(
					`could not resolve an index digest for ${subject.reference} (got: ${digest || "<empty>"})`,
				);
			}
			await pause(delayMs);
		}
		resolved.push({
			image: subject.image,
			indexDigest: digest,
			repository: subject.repository,
		});
	}
	return resolved;
}

/**
 * The hand-off between the resolver and `generate-release-evidence.ts`: image, repository and index
 * digest, one image per line. Repository travels with the digest so the generator never re-derives a
 * namespace the resolver already decided.
 */
export function formatResolvedImages(images: readonly ResolvedImage[]): string {
	return images
		.map(({ image, repository, indexDigest }) => `${image}\t${repository}\t${indexDigest}\n`)
		.join("");
}

export function parseResolvedImages(content: string): ResolvedImage[] {
	return content
		.split("\n")
		.filter((line) => line.length > 0)
		.map((line, index) => {
			const [image = "", repository = "", indexDigest = ""] = line.split("\t");
			if (image === "" || repository === "" || !DIGEST.test(indexDigest)) {
				throw new Error(`malformed resolved release image on line ${index + 1}: ${line}`);
			}
			return { image, indexDigest, repository };
		});
}

/** `<image>-digest=<digest>` step outputs, which `release.yml` reads for its downstream jobs. */
export function digestOutputs(images: readonly ResolvedImage[]): string {
	return images.map(({ image, indexDigest }) => `${image}-digest=${indexDigest}\n`).join("");
}

if (import.meta.main) {
	const [sourceTag = "", outputPath = ""] = process.argv.slice(2);
	if (sourceTag === "" || outputPath === "") {
		throw new Error("usage: resolve-release-images <source-tag> <output.tsv>");
	}
	const namespace = process.env.IMAGE_REGISTRY ?? "ghcr.io/hephaestus-build";
	const subjects = planSubjects(
		await readJsonFile("security/release-images.json"),
		namespace,
		sourceTag,
	);
	const images = await resolveReleaseImages(subjects);
	await writeFile(outputPath, formatResolvedImages(images));
	process.stdout.write(formatResolvedImages(images));
	const githubOutput = process.env.GITHUB_OUTPUT;
	if (isSet(githubOutput)) {
		await appendFile(githubOutput, digestOutputs(images));
	}
}
