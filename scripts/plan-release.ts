/**
 * Decides whether a commit on `main` cuts a release, and which release it follows.
 *
 * The question is *does the version on `main` have a published release yet*, not *did this commit
 * change the version*. A release that fails leaves the version consumed — the Version PR is merged
 * and the changesets it folded in are gone — so a decision keyed on the version commit can only be
 * retried by reverting that commit, which the changeset freeze rules block by construction and which
 * needed an administrator bypass all three times it was done (issues #1686, #1691, #1701). Keyed on
 * the version, the same release re-cuts from the next commit that carries the fix, which is how
 * everything else here recovers.
 *
 * That leaves four cases, in this order:
 *
 *   1. the version's release is published — the ordinary feature merge, and the only no-op;
 *   2. a draft of it targets another commit — refused, because resuming it would publish that
 *      commit's images under this tag; the operator deletes the draft to re-cut here;
 *   3. a draft of it targets this commit — cut, resuming the draft an attempt left behind;
 *   4. no release of it exists — cut, whether or not this commit changed the version.
 *
 * The precondition that a release must follow a published one compares against the latest published
 * release rather than the parent commit's version: on a re-cut the parent carries the same
 * unpublished version, and a parent comparison would refuse the very retry this exists to allow. It
 * is also what keeps the promotion of `X.Y`, `latest` and the staging deploy moving forward — a
 * version that is not newer than the latest published release is refused rather than cut.
 */
import { appendFile } from "node:fs/promises";

import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

/** Only a stable version counts; `0.9.0-rc.4` and friends are not part of the released line. */
const RELEASE_VERSION = /^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)$/u;

const MIGRATION_PATH = "server/application/src/main/resources/db/changelog/";

export interface ReleaseRef {
	readonly isDraft: boolean;
	readonly isPrerelease: boolean;
	readonly tag: string;
	/** The commit a draft was targeted at; a branch name for a release published from one. */
	readonly targetCommitish: string;
}

export interface ReleaseCut {
	readonly kind: "cut";
	readonly major: number;
	readonly minor: number;
	/** The latest published release, which the upgrade gate seeds from. */
	readonly previousVersion: string;
	/** A draft left by an attempt that cleared the evidence gate at this same commit. */
	readonly resumesDraft: boolean;
	readonly sha: string;
	readonly tag: string;
	readonly version: string;
}

export type ReleasePlan =
	| ReleaseCut
	| { readonly kind: "refuse"; readonly reason: string }
	| { readonly kind: "skip"; readonly reason: string };

interface Version {
	readonly major: number;
	readonly minor: number;
	readonly patch: number;
}

const parseVersion = (value: string): Version | undefined => {
	const groups = RELEASE_VERSION.exec(value)?.groups;
	if (groups === undefined) {
		return undefined;
	}
	return { major: Number(groups.major), minor: Number(groups.minor), patch: Number(groups.patch) };
};

const compareVersions = (left: Version, right: Version): number =>
	left.major - right.major || left.minor - right.minor || left.patch - right.patch;

const formatVersion = (version: Version): string =>
	`${version.major}.${version.minor}.${version.patch}`;

/** The highest published stable release: the one a new release follows and upgrades from. */
const latestPublished = (releases: readonly ReleaseRef[]): Version | undefined => {
	let latest: Version | undefined;
	for (const release of releases) {
		if (release.isDraft || release.isPrerelease || !release.tag.startsWith("v")) {
			continue;
		}
		const version = parseVersion(release.tag.slice(1));
		if (version && (!latest || compareVersions(version, latest) > 0)) {
			latest = version;
		}
	}
	return latest;
};

export function planRelease(
	sha: string,
	version: string,
	releases: readonly ReleaseRef[],
): ReleasePlan {
	const parsed = parseVersion(version);
	if (!parsed) {
		return { kind: "refuse", reason: `package version "${version}" is not major.minor.patch` };
	}
	const tag = `v${version}`;
	const existing = releases.find((release) => release.tag === tag);

	// The ordinary case and the only no-op: a feature merge carries a version published long ago, as
	// does a re-run of a workflow that already finished.
	if (existing && !existing.isDraft) {
		return { kind: "skip", reason: `${tag} is already published` };
	}

	// A draft exists only where an earlier attempt cleared the evidence gate and failed after it.
	if (existing && existing.targetCommitish !== sha) {
		return {
			kind: "refuse",
			reason: `draft ${tag} targets ${existing.targetCommitish}, not ${sha}; delete the draft to re-cut ${tag} here`,
		};
	}

	const previous = latestPublished(releases);
	if (!previous) {
		return { kind: "refuse", reason: `${tag} has no published release to follow` };
	}
	if (compareVersions(parsed, previous) <= 0) {
		return {
			kind: "refuse",
			reason: `${tag} is not newer than the latest published release v${formatVersion(previous)}`,
		};
	}

	return {
		kind: "cut",
		major: parsed.major,
		minor: parsed.minor,
		previousVersion: formatVersion(previous),
		resumesDraft: existing !== undefined,
		sha,
		tag,
		version,
	};
}

/**
 * The step outputs the rest of `release.yml` reads. A plan that cuts nothing writes only `released`,
 * which is what every downstream job is gated on.
 */
export function releaseOutputs(plan: ReleasePlan, migrations = false): Record<string, string> {
	if (plan.kind !== "cut") {
		return { released: "false" };
	}
	return {
		major: String(plan.major),
		migrations: String(migrations),
		minor: String(plan.minor),
		previous_version: plan.previousVersion,
		released: "true",
		sha: plan.sha,
		tag_name: plan.tag,
		version: plan.version,
	};
}

/** Every release, drafts included — which the listing carries only for a token with push access. */
async function listReleases(repository: string): Promise<ReleaseRef[]> {
	const listed = await output("gh", [
		"api",
		"--paginate",
		`repos/${repository}/releases?per_page=100`,
		// Projected rather than slurped whole: the release bodies are the changelog, and the full
		// listing already outgrows the default subprocess buffer.
		"--jq",
		".[] | {tag: .tag_name, draft: .draft, prerelease: .prerelease, commitish: .target_commitish}",
	]);
	return listed
		.split("\n")
		.filter((line) => line.trim() !== "")
		.map((line, index) => {
			const release = asRecord(parseJson(line), `gh api releases[${index}]`);
			return {
				isDraft: release.draft === true,
				isPrerelease: release.prerelease === true,
				tag: asString(release.tag, `gh api releases[${index}].tag_name`),
				targetCommitish: asString(release.commitish, `gh api releases[${index}].target_commitish`),
			};
		});
}

/**
 * Whether the release carries schema migrations, which the notes warn about. `--name-only` reports
 * the difference on success, so a git failure is an error here rather than a verdict — the warning
 * is never stamped, or omitted, by accident.
 */
export async function hasSchemaMigrations(
	from: string,
	to: string,
	cwd?: string,
): Promise<boolean> {
	const changed = await output("git", ["diff", "--name-only", from, to, "--", MIGRATION_PATH], {
		cwd,
		env: environmentWithoutGitRepository(),
	});
	return changed.trim() !== "";
}

if (import.meta.main) {
	const [sha] = process.argv.slice(2);
	const repository = process.env.GITHUB_REPOSITORY;
	if (sha === undefined || sha === "" || repository === undefined || repository === "") {
		console.log("::error::usage: GITHUB_REPOSITORY=<owner/repo> plan-release.ts <sha>");
		process.exitCode = 1;
	} else {
		// The version at the commit CI/CD built, not main's tip, which may carry a newer one.
		const manifest = asRecord(
			parseJson(
				await output("git", ["show", `${sha}:package.json`], {
					env: environmentWithoutGitRepository(),
				}),
			),
			`${sha}:package.json`,
		);
		const plan = planRelease(
			sha,
			asString(manifest.version, `${sha}:package.json version`),
			await listReleases(repository),
		);
		if (plan.kind === "refuse") {
			console.log(`::error::${plan.reason}`);
			process.exitCode = 1;
		} else {
			const migrations =
				plan.kind === "cut" && (await hasSchemaMigrations(`v${plan.previousVersion}`, sha));
			console.log(
				plan.kind === "cut"
					? `Cutting ${plan.tag} at ${sha} after v${plan.previousVersion}${plan.resumesDraft ? ", resuming its draft" : ""}`
					: `${plan.reason} — no release to cut.`,
			);
			const outputs = Object.entries(releaseOutputs(plan, migrations))
				.map(([name, value]) => `${name}=${value}\n`)
				.join("");
			const outputFile = process.env.GITHUB_OUTPUT;
			if (outputFile !== undefined && outputFile !== "") {
				await appendFile(outputFile, outputs);
			}
		}
	}
}
