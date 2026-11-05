import { execFileSync } from "node:child_process";

import { CAPTURE_LIMIT_BYTES } from "./process.ts";
import { compareVersions, parseVersion } from "./release-version.ts";

const stableVersion = (tag: string): number[] | undefined => {
	const groups = /^v(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)$/u.exec(tag)?.groups;
	return groups === undefined ? undefined : [groups.major, groups.minor, groups.patch].map(Number);
};

/**
 * The release the next one upgrades from: the highest stable `vX.Y.Z` tag merged into the head, but not a tag on the
 * head itself. On a release commit the guard so judges that release against the one before it.
 */
export function previousRelease(
	merged: readonly string[],
	onHead: readonly string[],
): string | undefined {
	return merged
		.filter((tag) => stableVersion(tag) !== undefined && !onHead.includes(tag))
		.toSorted((a, b) => {
			const [left = [], right = []] = [stableVersion(a), stableVersion(b)];
			const index = left.findIndex((part, position) => part !== right[position]);
			return index === -1 ? 0 : (right[index] ?? 0) - (left[index] ?? 0);
		})[0];
}

const tags = (...args: string[]): string[] =>
	execFileSync("git", ["tag", ...args], { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES })
		.split("\n")
		.filter(Boolean);

/** The release that `head` upgrades from, read from the tags of the local repository. */
export function previousReleaseOf(head: string): string | undefined {
	return previousRelease(tags("--merged", head, "--list", "v*"), tags("--points-at", head));
}

/**
 * The latest published release merged into the head, a release candidate included, but not a tag on the head
 * itself: what a database can run when the head's changelogs reach it. A break between two release candidates
 * matters as much as one between two stable releases.
 */
export function latestRelease(
	merged: readonly string[],
	onHead: readonly string[],
): string | undefined {
	return merged
		.filter((tag) => !onHead.includes(tag))
		.flatMap((tag) => {
			const version = tag.startsWith("v") ? parseVersion(tag.slice(1)) : undefined;
			return version === undefined ? [] : [{ tag, version }];
		})
		.toSorted((a, b) => compareVersions(b.version, a.version))[0]?.tag;
}

/** The latest published release that `head` contains, read from the tags of the local repository. */
export function latestReleaseOf(head: string): string | undefined {
	return latestRelease(tags("--merged", head, "--list", "v*"), tags("--points-at", head));
}

/** The 1.0 baseline refuses a database from before this release. */
export const BASELINE_RELEASE = "v1.0.0-rc.1";

/**
 * The release that an upgrade test of `head` starts from: the previous stable release, or the baseline's release while
 * the stable line is still 0.x, because the baseline refuses every 0.x database.
 */
export function upgradeFromOf(head: string): string | undefined {
	const previous = previousReleaseOf(head);
	const beforeBaseline = previous?.startsWith("v0.") ?? true;
	return beforeBaseline &&
		tags("--merged", head, "--list", BASELINE_RELEASE).length > 0 &&
		tags("--points-at", head, "--list", BASELINE_RELEASE).length === 0
		? BASELINE_RELEASE
		: previous;
}
