/**
 * Prints the tag of the release that the support statement supports, for the nightly release rescan.
 * docs/admin/compatibility-policy.mdx § Support statement owns the rule: before 1.0.0 the latest release
 * is supported, a release candidate included; from 1.0.0 the latest stable 1.x release is. GitHub's
 * "latest release" skips prereleases, so it kept naming 0.95.1 while the 1.0 candidates shipped.
 */
import { pathToFileURL } from "node:url";

import { requiredEnv } from "./lib/env.ts";
import { compareVersions, parseVersion, type Version } from "./lib/release-version.ts";
import { listReleases } from "./plan-release.ts";

const isStableFrom1 = (version: Version) => version.rc === undefined && version.major >= 1;

interface Published {
	readonly tag: string;
	readonly isDraft: boolean;
}

/** The supported release among the published ones. A tag that is not `v<version>` is not a release. */
export function supportedRelease(releases: readonly Published[]): string {
	const published = releases
		.filter((release) => !release.isDraft && release.tag.startsWith("v"))
		.flatMap((release) => {
			const version = parseVersion(release.tag.slice(1));
			return version === undefined ? [] : [{ tag: release.tag, version }];
		})
		.toSorted((left, right) => compareVersions(right.version, left.version));
	const supported = published.find(({ version }) => isStableFrom1(version)) ?? published[0];
	if (supported === undefined) {
		throw new Error("no published release");
	}
	return supported.tag;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
	const releases = await listReleases(requiredEnv(process.env, "GITHUB_REPOSITORY"));
	process.stdout.write(`${supportedRelease(releases)}\n`);
}
