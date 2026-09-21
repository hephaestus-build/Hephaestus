/**
 * Scans the upstream images `security/release-images.json` pins by digest.
 *
 * These four — alpine, nats, nginx, traefik — are shipped by `docker/compose.*.yaml` and covered by
 * the release evidence gate, but nothing built them, so neither the build-time gate in
 * `reusable-docker-build.yml` nor `scan-main-images.ts` had a subject for them. The gap was found by
 * a release: v0.75.0 failed at the evidence gate on a finding in the pinned alpine digest that had
 * been there for days (issue #1741).
 *
 * They need no build — a digest in a committed file is the whole subject — so this is cheap and
 * deterministic, it covers both released platforms, and it blocks by default. A Renovate digest
 * bump edits this very file, so the pull request proposing the bump is the one that scans it.
 *
 * `--report-only` is the weekly rescan, where a finding is routed to a tracking issue rather than a
 * red status: a CVE published after the digest was pinned belongs to no commit, and a pinned
 * upstream image cannot be patched by rebuilding anything here.
 */
import { writeFile } from "node:fs/promises";
import path from "node:path";

import { type ScanOutcome, scanAll, type Subject } from "./lib/image-scan.ts";
import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";

const DIGEST = /^sha256:[a-f0-9]{64}$/u;

/**
 * Both platforms, because the release evidence gate scans both and an exception matches on
 * `image | platform | vulnerability | package | installedVersion`. Scanning only `linux/amd64`
 * would leave an arm64-only finding — or an arm64 exception nobody wrote — discoverable for the
 * first time at the release, which is the failure this whole script exists to remove.
 */
export const PLATFORMS = ["linux/amd64", "linux/arm64"] as const;

export interface UpstreamSubject extends Subject {
	/** The multi-architecture index digest pinned in the inventory, which is what the release
	 * manifest records as each platform subject's `indexDigest`. */
	readonly indexDigest: string;
}

/** The pinned upstream images to scan, in inventory order. */
export function planUpstreamSubjects(inventory: unknown): UpstreamSubject[] {
	const upstream = asArray(asRecord(inventory, "release image inventory").upstream, "upstream");
	if (upstream.length === 0) {
		throw new Error("release image inventory lists no upstream images");
	}
	return upstream.map((value, index) => {
		const item = asRecord(value, `upstream[${index}]`);
		const image = asString(item.name, `upstream[${index}].name`);
		if (!/^[a-z0-9-]+$/u.test(image)) {
			throw new Error(`malformed upstream image name: ${image}`);
		}
		const repository = asString(item.repository, `upstream[${index}].repository`);
		const indexDigest = asString(item.digest, `upstream[${index}].digest`);
		if (!DIGEST.test(indexDigest)) {
			throw new Error(`malformed upstream image digest: ${image} (${indexDigest})`);
		}
		// By digest, never by tag: the tag is recorded for Renovate, and scanning it would scan
		// whatever it resolves to today rather than the artefact the release promotes.
		return { image, indexDigest, reference: `${repository}@${indexDigest}`, repository };
	});
}

if (import.meta.main) {
	const [directory = "reports", option] = process.argv.slice(2);
	if (option !== undefined && option !== "--report-only") {
		throw new Error("usage: scan-upstream-images <directory> [--report-only]");
	}
	const reportOnly = option === "--report-only";
	const subjects = planUpstreamSubjects(await readJsonFile("security/release-images.json"));
	const outcomes: ScanOutcome[] = [];
	for (const platform of PLATFORMS) {
		outcomes.push(...(await scanAll(subjects, directory, { annotate: !reportOnly, platform })));
	}
	await writeFile(
		path.join(directory, "upstream-scan.json"),
		`${JSON.stringify({ platforms: PLATFORMS, scannedAt: new Date().toISOString(), subjects }, null, 2)}\n`,
	);
	for (const outcome of outcomes) {
		process.stdout.write(
			`${outcome.image} (${outcome.platform}): ${outcome.passed ? "pass" : "fail"}\n`,
		);
	}
	const failed = outcomes
		.filter((outcome) => !outcome.passed)
		.map((outcome) => `${outcome.image} (${outcome.platform})`);
	if (failed.length > 0 && !reportOnly) {
		process.stderr.write(
			`::error::pinned upstream images do not satisfy the vulnerability policy: ${failed.join(", ")}\n`,
		);
		process.exitCode = 1;
	}
}
