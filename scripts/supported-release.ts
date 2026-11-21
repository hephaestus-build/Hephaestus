/**
 * Prints the supported release (`supportedRelease` in lib/previous-release.ts) for the nightly release rescan. It
 * reads the published releases, not GitHub's "latest release", which skips release candidates, and not the git tags,
 * because a tag can exist without a published release.
 */
import { requiredEnv } from "./lib/env.ts";
import { supportedRelease } from "./lib/previous-release.ts";
import { listReleases, type ReleaseRef } from "./plan-release.ts";

/** The supported release among the published releases. A draft is not published. */
export function supportedPublishedRelease(
	releases: readonly Pick<ReleaseRef, "tag" | "isDraft">[],
): string {
	const release = supportedRelease(
		releases.filter((entry) => !entry.isDraft).map((entry) => entry.tag),
	);
	if (release === undefined) {
		throw new Error("no published release");
	}
	return release;
}

if (import.meta.main) {
	const releases = await listReleases(requiredEnv(process.env, "GITHUB_REPOSITORY"));
	process.stdout.write(`${supportedPublishedRelease(releases)}\n`);
}
