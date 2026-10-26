/**
 * Decides whether a commit on `main` cuts a release, and which release it follows.
 *
 * A published version cuts nothing. An unpublished version waits while its source commit has
 * pending changesets: the Version PR owns their consumption, version bump and release notes.
 * Without pending changesets, the version can cut from a later forward fix or resume a draft at
 * the same commit. A draft at another commit is refused because its images belong to that commit.
 *
 * The upgrade seed is the latest published stable release, not the parent commit's version.
 * The new version must be newer than that release before image aliases can move forward.
 * The release notes start from the same release (`--notes`).
 *
 * The plan is read before the promotion lock, so another run of the same version can write first.
 * Each locked step that writes or publishes the release checks its source again (`--source`).
 */
import { appendFile, readFile } from "node:fs/promises";

import { isSet } from "./lib/env.ts";
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

export const parseVersion = (value: string): Version | undefined => {
	const groups = RELEASE_VERSION.exec(value)?.groups;
	if (groups === undefined) {
		return undefined;
	}
	return { major: Number(groups.major), minor: Number(groups.minor), patch: Number(groups.patch) };
};

export const compareVersions = (left: Version, right: Version): number =>
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

/** Alias writes must not let a retry of an older release undo a newer publication. */
export function assertCurrentStableRelease(version: string, releases: readonly ReleaseRef[]): void {
	const latest = latestPublished(releases);
	if (latest === undefined || version !== formatVersion(latest)) {
		throw new Error("Only the current stable release may promote image aliases");
	}
}

/** What GitHub holds for a release tag now. */
export interface ReleaseSource {
	/** The release with this tag, draft or published; `undefined` when there is none. */
	readonly release: { readonly isDraft: boolean; readonly targetCommitish: string } | undefined;
	/** The commit the Git tag names; `undefined` while no tag exists, as for a draft. */
	readonly tagCommit: string | undefined;
}

export type ReleaseSourceState = "absent" | "draft" | "published";

const SOURCE_STATES: ReadonlySet<string> = new Set(["absent", "draft", "published"]);

/**
 * Where the release of `tag` stands for `sha`, the commit whose evidence a step is about to write.
 * A draft or tag of another commit means another run established this version's source first, so
 * this run must not attach to it, publish it or promote from it. A published release binds through
 * its tag: a draft target does not move a tag that already exists.
 */
export function releaseSourceState(
	tag: string,
	sha: string,
	source: ReleaseSource,
): ReleaseSourceState {
	if (source.tagCommit !== undefined && source.tagCommit !== sha) {
		throw new Error(`tag ${tag} points to ${source.tagCommit}, not ${sha}`);
	}
	if (source.release === undefined) {
		return "absent";
	}
	if (source.release.isDraft) {
		if (source.release.targetCommitish !== sha) {
			throw new Error(`draft ${tag} targets ${source.release.targetCommitish}, not ${sha}`);
		}
		return "draft";
	}
	if (source.tagCommit === undefined) {
		throw new Error(`published ${tag} has no tag that binds it to ${sha}`);
	}
	return "published";
}

export function planRelease(
	sha: string,
	version: string,
	releases: readonly ReleaseRef[],
	pendingChangesets: boolean,
): ReleasePlan {
	const parsed = parseVersion(version);
	if (!parsed) {
		return { kind: "refuse", reason: `package version "${version}" is not major.minor.patch` };
	}
	const tag = `v${version}`;
	const existing = releases.find((release) => release.tag === tag);

	// Published releases are never cut again, including on feature merges with pending changesets.
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

	if (pendingChangesets) {
		return {
			kind: "skip",
			reason: `pending changesets remain at ${sha}; the Version PR must consume them before releasing ${tag}`,
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

/**
 * The release notes of `version`: every CHANGELOG section newer than the published release
 * `previousVersion`, newest first, as the source has them. A version that never published reaches
 * operators through the next one that does. Only top-level `##` headings bound a section, so a
 * heading inside a fenced example is text. Throws, before any release exists, where the sections
 * from `version` down to `previousVersion` are missing, repeated, out of order or not stable.
 */
export async function renderReleaseNotes(
	changelog: string,
	previousVersion: string,
	version: string,
): Promise<string> {
	// Loaded here, not at the top: the planner itself runs where no dependency is installed.
	const { fromMarkdown } = await import("mdast-util-from-markdown");
	const previous = parseVersion(previousVersion);
	const current = parseVersion(version);
	if (!previous || !current) {
		throw new Error(
			`release notes: "${previousVersion}" and "${version}" must both be major.minor.patch`,
		);
	}
	if (compareVersions(current, previous) <= 0) {
		throw new Error(`release notes: ${version} is not newer than ${previousVersion}`);
	}
	const sections = fromMarkdown(changelog).children.flatMap((node) => {
		if (node.type !== "heading" || node.depth !== 2) {
			return [];
		}
		const start = node.position?.start.offset;
		const end = node.position?.end.offset;
		if (start === undefined || end === undefined) {
			throw new Error("release notes: a CHANGELOG heading has no source position");
		}
		const heading = changelog.slice(start, end);
		const named = /^## (?<version>\S+)$/u.exec(heading)?.groups?.version;
		return [{ heading, start, version: named === undefined ? undefined : parseVersion(named) }];
	});
	for (const bound of [`## ${version}`, `## ${previousVersion}`]) {
		const count = sections.filter((section) => section.heading === bound).length;
		if (count !== 1) {
			throw new Error(`release notes: CHANGELOG has ${count} "${bound}" sections, not one`);
		}
	}
	const [newest, ...older] = sections;
	if (newest?.heading !== `## ${version}`) {
		throw new Error(
			`release notes: the newest CHANGELOG section is "${newest?.heading ?? "none"}", not "## ${version}"`,
		);
	}
	let newer = current;
	for (const section of older) {
		if (!section.version || compareVersions(section.version, newer) >= 0) {
			throw new Error(
				`release notes: "${section.heading}" is not a stable release older than ${formatVersion(newer)}`,
			);
		}
		if (compareVersions(section.version, previous) === 0) {
			return changelog.slice(newest.start, section.start);
		}
		newer = section.version;
	}
	// Unreachable while the seed heading exists once and every section above it descends.
	throw new Error(`release notes: no "## ${previousVersion}" section below "## ${version}"`);
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
			if (typeof release.draft !== "boolean" || typeof release.prerelease !== "boolean") {
				throw new Error(`gh api releases[${index}] must have boolean draft and prerelease flags`);
			}
			return {
				isDraft: release.draft,
				isPrerelease: release.prerelease,
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

/** Pending release notes belong to the validated source commit, not the current checkout. */
export async function hasPendingChangesets(sha: string, cwd?: string): Promise<boolean> {
	const files = await output(
		"git",
		["ls-tree", "-r", "--name-only", "-z", sha, "--", ".changeset"],
		{
			cwd,
			env: environmentWithoutGitRepository(),
		},
	);
	return files
		.split("\0")
		.some((file) => /^\.changeset\/[^/]+\.md$/u.test(file) && file !== ".changeset/README.md");
}

/** The release with `tag`, drafts included. Only gh's own not-found answer means there is none. */
async function readRelease(repository: string, tag: string): Promise<ReleaseSource["release"]> {
	let viewed: string;
	try {
		viewed = await output("gh", [
			"release",
			"view",
			tag,
			"--repo",
			repository,
			"--json",
			"isDraft,targetCommitish",
		]);
	} catch (error) {
		const stderr = error instanceof Error && "stderr" in error ? error.stderr : undefined;
		if (typeof stderr === "string" && stderr.trim() === "release not found") {
			return undefined;
		}
		throw error;
	}
	const release = asRecord(parseJson(viewed), `gh release view ${tag}`);
	if (typeof release.isDraft !== "boolean") {
		throw new Error(`gh release view ${tag} must have a boolean isDraft flag`);
	}
	return {
		isDraft: release.isDraft,
		targetCommitish: asString(release.targetCommitish, `gh release view ${tag}.targetCommitish`),
	};
}

/** The commit the Git tag `tag` names, through any annotated tag objects; `undefined` without one. */
async function readTagCommit(repository: string, tag: string): Promise<string | undefined> {
	const ref = `refs/tags/${tag}`;
	// An absent tag is an empty listing, so a failed read never passes for an absent tag.
	const listed = await output("gh", [
		"api",
		"--paginate",
		`repos/${repository}/git/matching-refs/tags/${tag}`,
		"--jq",
		".[] | {ref, type: .object.type, sha: .object.sha}",
	]);
	const match = listed
		.split("\n")
		.filter((line) => line.trim() !== "")
		.map((line, index) => asRecord(parseJson(line), `gh api matching-refs[${index}]`))
		.find((candidate) => candidate.ref === ref);
	if (match === undefined) {
		return undefined;
	}
	let type = asString(match.type, `${ref} object type`);
	let sha = asString(match.sha, `${ref} object sha`);
	// An annotated tag names a tag object, which names the commit.
	for (let depth = 0; type === "tag"; depth += 1) {
		if (depth === 4) {
			throw new Error(`${ref} nests more than four tag objects`);
		}
		const label = `tag object ${sha}`;
		const object = asRecord(
			parseJson(
				await output("gh", ["api", `repos/${repository}/git/tags/${sha}`, "--jq", ".object"]),
			),
			label,
		);
		type = asString(object.type, `${label} type`);
		sha = asString(object.sha, `${label} sha`);
	}
	if (type !== "commit") {
		throw new Error(`${ref} names a ${type}, not a commit`);
	}
	return sha;
}

if (import.meta.main && process.argv[2] === "--promote") {
	const [, version] = process.argv.slice(2);
	const repository = process.env.GITHUB_REPOSITORY;
	if (!isSet(version) || !isSet(repository)) {
		console.log(
			"::error::usage: GITHUB_REPOSITORY=<owner/repo> plan-release.ts --promote <version>",
		);
		process.exitCode = 1;
	} else {
		try {
			assertCurrentStableRelease(version, await listReleases(repository));
		} catch (error) {
			console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
			process.exitCode = 1;
		}
	}
} else if (import.meta.main && process.argv[2] === "--notes") {
	// Appends to a file rather than stdout, so a refusal reaches the log and not the notes.
	const [, previousVersion, version, changelog, notes] = process.argv.slice(2);
	if (!isSet(previousVersion) || !isSet(version) || !isSet(changelog) || !isSet(notes)) {
		console.log(
			"::error::usage: plan-release.ts --notes <previous-version> <version> <CHANGELOG.md> <notes-file>",
		);
		process.exitCode = 1;
	} else {
		try {
			await appendFile(
				notes,
				await renderReleaseNotes(await readFile(changelog, "utf8"), previousVersion, version),
			);
		} catch (error) {
			console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
			process.exitCode = 1;
		}
	}
} else if (import.meta.main && process.argv[2] === "--source") {
	// Prints the state for the step to branch on, and refuses a state the step does not accept.
	const [, tag, sha, ...accepted] = process.argv.slice(2);
	const repository = process.env.GITHUB_REPOSITORY;
	if (
		!isSet(tag) ||
		!isSet(sha) ||
		!isSet(repository) ||
		accepted.length === 0 ||
		accepted.some((state) => !SOURCE_STATES.has(state))
	) {
		console.log(
			"::error::usage: GITHUB_REPOSITORY=<owner/repo> plan-release.ts --source <tag> <sha> <absent|draft|published>...",
		);
		process.exitCode = 1;
	} else {
		try {
			const state = releaseSourceState(tag, sha, {
				release: await readRelease(repository, tag),
				tagCommit: await readTagCommit(repository, tag),
			});
			if (!accepted.includes(state)) {
				throw new Error(`${tag} is ${state}; this step accepts only ${accepted.join(" or ")}`);
			}
			console.log(state);
		} catch (error) {
			console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
			process.exitCode = 1;
		}
	}
} else if (import.meta.main) {
	const [sha] = process.argv.slice(2);
	const repository = process.env.GITHUB_REPOSITORY;
	if (!isSet(sha) || !isSet(repository)) {
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
			await hasPendingChangesets(sha),
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
			if (isSet(outputFile)) {
				await appendFile(outputFile, outputs);
			}
		}
	}
}
