/**
 * Rescans the first-party images built from `main` against the release vulnerability policy.
 *
 * Container CVEs are time-dependent, not commit-dependent: the commit that was clean when it merged
 * becomes vulnerable days later with nothing in the repository having changed. The build-time gate in
 * `reusable-docker-build.yml` catches change-driven regressions and cannot catch this, so v0.75.0 was
 * blocked by findings whose first appearance was the release itself.
 *
 * This walks the `images` half of `security/release-images.json`; the pinned upstream half is
 * `scan-upstream-images.ts`, and `ci-contract.test.ts` asserts the two together cover exactly what
 * the release evidence gate demands. Both share `lib/image-scan.ts`, which hands each Trivy report
 * to `check-release-vulnerabilities.ts`.
 *
 * Only `linux/amd64`, unlike the upstream scan, which covers both. A finding on either architecture
 * of an image we build is fixed by the same rebuild, and the Version PR preflight scans both before
 * a release is cut; a pinned upstream digest has no rebuild and its fix arrives as a digest bump in
 * a pull request, so that scan cannot leave half a subject to the release gate.
 *
 * A finding never fails this run. `report-vulnerability-drift.ts` reads the `.policy.json` files
 * written here and routes them to a tracking issue; only an infrastructure failure — a missing tag, an
 * unreachable registry, a Trivy crash — is worth a red status on a schedule nobody triggered.
 */
import { writeFile } from "node:fs/promises";
import path from "node:path";

import { PLATFORM, scanAll, type Subject } from "./lib/image-scan.ts";
import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";

/**
 * The first-party images to scan, in inventory order.
 *
 * `reusable-docker-build.yml` tags every push with `github.ref_name`, so `<registry>/<owner>/<image>:main`
 * already names main's HEAD build and nothing new has to be published for this to have a subject.
 */
export function planSubjects(inventory: unknown, registry: string, tag: string): Subject[] {
	const images = asArray(asRecord(inventory, "release image inventory").images, "images");
	if (images.length === 0) {
		throw new Error("release image inventory lists no images");
	}
	return images.map((value, index) => {
		const image = asString(value, `images[${index}]`);
		if (!/^[a-z0-9-]+$/u.test(image)) {
			throw new Error(`malformed release image name: ${image}`);
		}
		const repository = `${registry}/${image}`;
		return { image, reference: `${repository}:${tag}`, repository };
	});
}

if (import.meta.main) {
	const [directory = "reports"] = process.argv.slice(2);
	const registry = process.env.IMAGE_REGISTRY ?? "ghcr.io/hephaestus-build";
	const tag = process.env.IMAGE_TAG ?? "main";
	const subjects = planSubjects(await readJsonFile("security/release-images.json"), registry, tag);
	const outcomes = await scanAll(subjects, directory);
	await writeFile(
		path.join(directory, "scan.json"),
		`${JSON.stringify({ platform: PLATFORM, registry, scannedAt: new Date().toISOString(), subjects, tag }, null, 2)}\n`,
	);
	for (const outcome of outcomes) {
		process.stdout.write(`${outcome.image}: ${outcome.passed ? "pass" : "fail"}\n`);
	}
}
