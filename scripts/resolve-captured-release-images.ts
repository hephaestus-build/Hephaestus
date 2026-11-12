/** Resolve the release's captured image inventory without consulting mutable registry tags. */
import { writeFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import type { Subject } from "./lib/image-scan.ts";
import { asRecord, asString, parseJson, readJsonFile } from "./lib/json.ts";
import { formatResolvedImages, type ResolvedImage } from "./resolve-release-images.ts";
import { planSubjects } from "./scan-main-images.ts";

const DIGEST = /^sha256:[a-f0-9]{64}$/u;

/** Use exactly the images the release captured, without resolving mutable tags again. */
export function capturedReleaseImages(
	subjects: readonly Subject[],
	input: string,
): ResolvedImage[] {
	const digests = asRecord(parseJson(input), "captured image digests");
	const expected = subjects.map(({ image }) => image).toSorted();
	if (JSON.stringify(Object.keys(digests).toSorted()) !== JSON.stringify(expected)) {
		throw new Error("captured image digests must contain exactly the release image inventory");
	}
	return subjects.map(({ image, repository }) => {
		const indexDigest = asString(digests[image], `captured ${image} digest`);
		if (!DIGEST.test(indexDigest)) {
			throw new Error(`invalid captured ${image} digest`);
		}
		return { image, repository, indexDigest };
	});
}

if (import.meta.main) {
	const [outputPath = "", ...extra] = process.argv.slice(2);
	if (outputPath === "" || extra.length > 0) {
		throw new Error("usage: resolve-captured-release-images <output.tsv>");
	}
	const subjects = planSubjects(
		await readJsonFile("security/release-images.json"),
		process.env.IMAGE_REGISTRY ?? "ghcr.io/hephaestus-build",
		"captured",
	);
	const images = capturedReleaseImages(subjects, requiredEnv(process.env, "RELEASE_IMAGE_DIGESTS"));
	await writeFile(outputPath, formatResolvedImages(images));
	process.stdout.write(formatResolvedImages(images));
}
