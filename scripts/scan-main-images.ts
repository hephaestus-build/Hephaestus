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
 * Both platforms, as `scan-upstream-images.ts` explains: an arm64-only finding left to the Version
 * PR preflight is a finding whose first appearance blocks the release.
 *
 * A finding never fails this run. `report-vulnerability-drift.ts` reads the `.policy.json` files
 * written here and routes them to a tracking issue; only an infrastructure failure — a missing tag, an
 * unreachable registry, a Trivy crash — is worth a red status on a schedule nobody triggered.
 */
import { writeFile } from "node:fs/promises";
import path from "node:path";

import { type ScanOutcome, scanAll, type Subject } from "./lib/image-scan.ts";
import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { PLATFORMS } from "./scan-upstream-images.ts";

/**
 * The first-party images to scan, in inventory order.
 *
 * `reusable-docker-build.yml` moves the `github.ref_name` tag forward on every push, so
 * `<registry>/<owner>/<image>:main` names the newest main build that published, and nothing new has to
 * be published for this to have a subject.
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
	const outcomes: ScanOutcome[] = [];
	for (const platform of PLATFORMS) {
		outcomes.push(...(await scanAll(subjects, directory, { platform })));
	}
	await writeFile(
		path.join(directory, "scan.json"),
		`${JSON.stringify({ platforms: PLATFORMS, registry, scannedAt: new Date().toISOString(), subjects, tag }, null, 2)}\n`,
	);
	for (const outcome of outcomes) {
		process.stdout.write(
			`${outcome.image} (${outcome.platform}): ${outcome.passed ? "pass" : "fail"}\n`,
		);
	}
}
