/**
 * Decides whether a commit on `main` cuts a release, and which release it follows.
 *
 * A published version cuts nothing. An unpublished version waits while its source commit has
 * pending changesets: the Version PR owns their consumption, version bump and release notes.
 * Without pending changesets, the version can cut from a later forward fix or resume a draft at
 * the same commit. A draft at another commit is refused because its images belong to that commit.
 * A commit with pending changesets cuts the unpublished version from the commit that set it, when
 * CI/CD validated that commit and only new changesets followed it (`planVersionRelease`).
 *
 * The upgrade seed is the latest published stable release, not the parent commit's version.
 * The new version must be newer than that release before image aliases can move forward.
 * The release notes start from the same release (`--notes`).
 *
 * The plan is read before the promotion lock, so another run of the same version can write first.
 * Each locked step that writes or publishes the release checks its source again (`--source`).
 */
import { appendFile, readdir, readFile } from "node:fs/promises";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { BASELINE_RELEASE } from "./lib/previous-release.ts";
import { output } from "./lib/process.ts";
import { releaseHistoryProblems } from "./lib/release-headings.ts";
import {
	compareVersions,
	formatVersion,
	imageAliases,
	parseVersion,
	stableVersion,
	type Version,
} from "./lib/release-version.ts";

export { compareVersions, parseVersion } from "./lib/release-version.ts";

const MIGRATION_PATH = "server/application/src/main/resources/db/changelog/";

export interface ReleaseRef {
	readonly isDraft: boolean;
	readonly isPrerelease: boolean;
	readonly isImmutable?: boolean;
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
	/** An immutable RC whose source and image digests this stable release promotes. */
	readonly candidate?: string;
}

export type ReleasePlan =
	| ReleaseCut
	| { readonly kind: "refuse"; readonly reason: string }
	| { readonly kind: "skip"; readonly reason: string };

/** The highest published stable release: the one a new release follows and upgrades from. */
const latestPublished = (releases: readonly ReleaseRef[]): Version | undefined => {
	let latest: Version | undefined;
	for (const release of releases) {
		if (release.isDraft || release.isPrerelease || !release.tag.startsWith("v")) {
			continue;
		}
		const version = parseVersion(release.tag.slice(1));
		if (version && version.rc === undefined && (!latest || compareVersions(version, latest) > 0)) {
			latest = version;
		}
	}
	return latest;
};

/** Alias writes must not let a retry of an older release undo a newer publication. */
export function assertCurrentStableRelease(version: string, releases: readonly ReleaseRef[]): void {
	const parsed = parseVersion(version);
	if (parsed?.rc !== undefined) {
		if (
			!releases.some(
				(release) =>
					release.tag === `v${version}` &&
					!release.isDraft &&
					release.isPrerelease &&
					release.isImmutable === true,
			)
		) {
			throw new Error("Only a published immutable candidate may promote its version alias");
		}
		return;
	}
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
	if (
		parsed.rc === undefined &&
		releases.some((release) => {
			const candidate = parseVersion(release.tag.replace(/^v/u, ""));
			return (
				!release.isDraft &&
				release.isPrerelease &&
				candidate?.rc !== undefined &&
				stableVersion(candidate) === version
			);
		})
	) {
		return {
			kind: "refuse",
			reason: `${tag} must promote its qualified candidate; a stable Version PR must not rebuild it`,
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
		// The 1.0 baseline refuses every 0.x database, so a 1.0 candidate upgrades from the baseline's release.
		previousVersion:
			parsed.major >= 1 &&
			previous.major === 0 &&
			releases.some((release) => release.tag === BASELINE_RELEASE && !release.isDraft)
				? BASELINE_RELEASE.slice(1)
				: formatVersion(previous),
		resumesDraft: existing !== undefined,
		sha,
		tag,
		version,
	};
}

/** The commit that set the version a later commit carries, and what changed after it. */
export interface VersionSource {
	readonly sha: string;
	/** The source still has changesets, so no Version PR versioned it. */
	readonly pendingChangesets: boolean;
	/** A successful CI/CD push or merge-group run validated this exact commit. */
	readonly validated: boolean;
	/** Release history that the commits after the source changed, as `git diff --name-status` lines. */
	readonly historyChanges: readonly string[];
	/**
	 * A successful CI/CD push run started at the source or a later commit before this one. Its
	 * release run planned the version first, so this push keeps the old answer: it skips.
	 */
	readonly plannedBefore: boolean;
}

/**
 * Plans the release of the version that `sha` carries. A commit without pending changesets plans
 * itself, so a forward fix can still recut an unpublished version. A commit with pending changesets
 * merged after the Version PR. When the merge queue folds the Version PR into a group with later
 * pull requests, `main` receives one push, for the group's tip, and no push run starts at the
 * commit that changed the version. The first push that brings that commit to `main` then cuts it,
 * but only if CI/CD validated it and the commits after it changed no release history: they may add
 * changesets for the next Version PR, and nothing else that a release reads. A later push skips:
 * one release run per version, and a forward fix after it plans itself.
 */
export function planVersionRelease(
	sha: string,
	version: string,
	releases: readonly ReleaseRef[],
	pendingChangesets: boolean,
	source: VersionSource | undefined,
): ReleasePlan {
	if (!pendingChangesets || source === undefined || source.sha === sha || source.plannedBefore) {
		return planRelease(sha, version, releases, pendingChangesets);
	}
	const plan = planRelease(source.sha, version, releases, source.pendingChangesets);
	if (plan.kind !== "cut") {
		return plan;
	}
	if (!source.validated) {
		return {
			kind: "refuse",
			reason: `${plan.tag} would cut at its version commit ${source.sha}, but no successful CI/CD push or merge-group run validated that commit`,
		};
	}
	if (source.historyChanges.length > 0) {
		return {
			kind: "refuse",
			reason: `${plan.tag} would cut at its version commit ${source.sha}, but the commits after it changed release history (${source.historyChanges.join("; ")}); only new changesets may follow it`,
		};
	}
	return plan;
}

const RELEASE_HISTORY = ["CHANGELOG.md", "MIGRATION.md", ".changeset", ".migration"];

/** `git diff --name-status -z` output as `status<TAB>path` lines, so no path is quoted. */
export function nameStatusLines(text: string): string[] {
	const fields = text.split("\0").filter((field) => field !== "");
	const lines: string[] = [];
	for (let index = 0; index + 1 < fields.length; index += 2) {
		lines.push(`${fields[index] ?? ""}\t${fields[index + 1] ?? ""}`);
	}
	return lines;
}

/** A change after the version commit that a release reads: anything but a new changeset or fragment. */
export function isHistoryChange(line: string): boolean {
	const [status = "", file = ""] = line.split("\t");
	if (file === "CHANGELOG.md" || file === "MIGRATION.md") {
		return true;
	}
	if (file === ".changeset/pre.json" || file.startsWith(".changeset/pre/")) {
		return true;
	}
	const note = /^\.changeset\/[^/]+\.md$/u.test(file) && file !== ".changeset/README.md";
	const fragment = /^\.migration\/[^/]+\.md$/u.test(file) && file !== ".migration/README.md";
	// A new note waits for the next Version PR; a consumed or edited one was released or rewritten.
	return (note || fragment) && status !== "A";
}

/**
 * The newest first-parent commit at or before `sha` whose `package.json` version differs from its
 * first parent's, with its pending changesets, the release history changed after it, and the
 * first-parent commits from it up to `sha` (`sha` excluded). `undefined` when the history within
 * `limit` commits does not reach it.
 */
export async function findVersionSource(
	sha: string,
	cwd?: string,
	limit = 1000,
): Promise<
	| (Omit<VersionSource, "validated" | "plannedBefore"> & { readonly earlier: readonly string[] })
	| undefined
> {
	const env = environmentWithoutGitRepository();
	const git = async (args: string[]) => output("git", args, { cwd, env });
	const versionAt = async (revision: string): Promise<string | undefined> => {
		let manifest: string;
		try {
			manifest = await git(["show", `${revision}:package.json`]);
		} catch {
			return undefined;
		}
		return asString(
			asRecord(parseJson(manifest), `${revision}:package.json`).version,
			`${revision}:package.json version`,
		);
	};
	const version = await versionAt(sha);
	const listed = await git(["rev-list", "--first-parent", `--max-count=${limit}`, sha]);
	const commits = listed.split("\n").filter((line) => line !== "");
	let source: string | undefined;
	for (const commit of commits) {
		if ((await versionAt(commit)) !== version) {
			break;
		}
		source = commit;
	}
	// The walk must end at a commit with another version; a history cut off by `limit` names none.
	if (source === undefined || source === commits.at(-1)) {
		return undefined;
	}
	const changed = await git([
		"diff",
		"--name-status",
		"--no-renames",
		"-z",
		source,
		sha,
		"--",
		...RELEASE_HISTORY,
	]);
	return {
		sha: source,
		pendingChangesets: await hasPendingChangesets(source, cwd),
		historyChanges: nameStatusLines(changed).filter(isHistoryChange),
		earlier: commits.slice(1, commits.indexOf(source) + 1),
	};
}

/** Whether a successful CI/CD push or merge-group run of this repository validated `sha`. */
async function validatedByCi(repository: string, sha: string): Promise<boolean> {
	const response = asRecord(
		parseJson(
			await output("gh", [
				"api",
				`repos/${repository}/actions/workflows/cicd.yml/runs?head_sha=${sha}&status=success&per_page=50`,
			]),
		),
		"workflow runs",
	);
	const runs = response.workflow_runs;
	if (!Array.isArray(runs)) {
		throw new Error("gh api workflow runs must list workflow_runs");
	}
	return runs.some((value) => {
		const run = asRecord(value, "workflow run");
		return (
			(run.event === "push" || run.event === "merge_group") &&
			run.head_sha === sha &&
			isRecord(run.head_repository) &&
			run.head_repository.full_name === repository
		);
	});
}

/** The commits on `main` whose CI/CD push run succeeded: each started a release run. */
async function pushedCommits(repository: string): Promise<Set<string>> {
	const response = asRecord(
		parseJson(
			await output("gh", [
				"api",
				`repos/${repository}/actions/workflows/cicd.yml/runs?branch=main&event=push&status=success&per_page=100`,
			]),
		),
		"workflow runs",
	);
	const runs = response.workflow_runs;
	if (!Array.isArray(runs)) {
		throw new Error("gh api workflow runs must list workflow_runs");
	}
	return new Set(
		runs
			.map((value) => asRecord(value, "workflow run"))
			.filter(
				(run) => isRecord(run.head_repository) && run.head_repository.full_name === repository,
			)
			.map((run) => asString(run.head_sha, "head sha")),
	);
}

/** The commit that set the version `sha` carries, when it is another commit; `undefined` otherwise. */
async function versionSource(repository: string, sha: string): Promise<VersionSource | undefined> {
	const found = await findVersionSource(sha);
	if (found === undefined || found.sha === sha) {
		return undefined;
	}
	const pushed = await pushedCommits(repository);
	return {
		...found,
		plannedBefore: found.earlier.some((commit) => pushed.has(commit)),
		validated: await validatedByCi(repository, found.sha),
	};
}

/** Rechecked under each publication lock so a newer RC cannot be overtaken by promotion. */
export function assertPromotionCandidate(
	sha: string,
	candidate: string,
	releases: readonly ReleaseRef[],
): Version {
	if (!/^[a-f0-9]{40}$/u.test(sha)) {
		throw new Error("Promotion needs the full main commit SHA");
	}
	const version = candidate.startsWith("v") ? parseVersion(candidate.slice(1)) : undefined;
	if (version?.rc === undefined) {
		throw new Error("Promotion needs a vX.Y.Z-rc.N candidate");
	}
	const source = releases.find((release) => release.tag === candidate);
	if (
		!source ||
		source.isDraft ||
		!source.isPrerelease ||
		source.isImmutable !== true ||
		source.targetCommitish !== sha
	) {
		throw new Error("Promotion needs the immutable published candidate of this exact main commit");
	}
	for (const release of releases) {
		const other = release.tag.startsWith("v") ? parseVersion(release.tag.slice(1)) : undefined;
		if (
			other?.rc !== undefined &&
			stableVersion(other) === stableVersion(version) &&
			compareVersions(other, version) > 0
		) {
			throw new Error(`A newer candidate exists: ${release.tag}`);
		}
	}
	return version;
}

export function planCandidatePromotion(
	sha: string,
	candidate: string,
	releases: readonly ReleaseRef[],
	pendingChangesets: boolean,
): ReleasePlan {
	let version: Version;
	try {
		version = assertPromotionCandidate(sha, candidate, releases);
	} catch (error) {
		return { kind: "refuse", reason: error instanceof Error ? error.message : String(error) };
	}
	if (pendingChangesets) {
		return { kind: "refuse", reason: "A candidate with pending changesets cannot be promoted" };
	}
	// Candidate identity is checked above. Ordinary stable planning deliberately refuses this path.
	const plan = planRelease(
		sha,
		stableVersion(version),
		releases.filter((release) => !release.isPrerelease),
		false,
	);
	return plan.kind === "cut" ? { ...plan, candidate } : plan;
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
		candidate: plan.candidate ?? "",
		major: String(plan.major),
		migrations: String(migrations),
		minor: String(plan.minor),
		previous_version: plan.previousVersion,
		prerelease: String(parseVersion(plan.version)?.rc !== undefined),
		notes_version: plan.candidate?.slice(1) ?? plan.version,
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
 * from `version` down to `previousVersion` are missing, repeated, out of order or not release versions.
 *
 * `releaseVersion` names the stable release that promotes the candidate `version`. When it starts a
 * new major, the summaries of the consumed `major` changesets (`majorChangesets`) lead the notes as
 * `### Major Changes`: the new major's promise and its operator action come first, and a bounded
 * release description keeps them. Released text stays as it is, so each candidate section keeps its
 * own historical entry.
 */
export async function renderReleaseNotes(
	changelog: string,
	previousVersion: string,
	version: string,
	releaseVersion?: string,
	majorChangesets: readonly string[] = [],
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
				`release notes: "${section.heading}" is not a release older than ${formatVersion(newer)}`,
			);
		}
		if (compareVersions(section.version, previous) === 0) {
			const notes = changelog.slice(newest.start, section.start);
			return releaseVersion === undefined || !startsMajor(releaseVersion, current, previous)
				? notes
				: majorLead(releaseVersion, majorChangesets) + notes;
		}
		newer = section.version;
	}
	// Unreachable while the seed heading exists once and every section above it descends.
	throw new Error(`release notes: no "## ${previousVersion}" section below "## ${version}"`);
}

/** Whether the stable `releaseVersion` promotes the candidate `candidate` into a new major. */
function startsMajor(releaseVersion: string, candidate: Version, previous: Version): boolean {
	const release = parseVersion(releaseVersion);
	if (!release || release.rc !== undefined) {
		throw new Error(
			`release notes: "${releaseVersion}" must be a stable major.minor.patch release`,
		);
	}
	if (candidate.rc === undefined || stableVersion(candidate) !== stableVersion(release)) {
		throw new Error(
			`release notes: ${releaseVersion} does not promote the candidate ${formatVersion(candidate)}`,
		);
	}
	return release.major > previous.major;
}

/** The consumed changesets that versioning keeps in `.changeset/pre/` beside the CHANGELOG. */
async function consumedChangesets(root: string): Promise<string[]> {
	const directory = path.join(root, ".changeset", "pre");
	const entries = await readdir(directory);
	const names = entries.filter((name) => name.endsWith(".md")).toSorted();
	return Promise.all(names.map(async (name) => readFile(path.join(directory, name), "utf8")));
}

/** The `### Major Changes` lead of a new major: each consumed major changeset's summary, as Changesets lists it. */
function majorLead(releaseVersion: string, summaries: readonly string[]): string {
	if (summaries.length === 0) {
		throw new Error(
			`release notes: ${releaseVersion} starts a major, but .changeset/pre/ has no consumed major changeset`,
		);
	}
	const entries = summaries.map((summary) =>
		summary
			.trim()
			.split("\n")
			.map((line, index) => {
				if (index === 0) {
					return `- ${line}`;
				}
				return line === "" ? "" : `  ${line}`;
			})
			.join("\n"),
	);
	return `### Major Changes\n\n${entries.join("\n\n")}\n\n`;
}

/** The summary of each consumed changeset that bumps `hephaestus` by a major. */
export function majorChangesetSummaries(changesets: readonly string[]): string[] {
	return changesets.flatMap((changeset) => {
		const parts = /^---\n(?<front>[\s\S]*?)\n---\n(?<summary>[\s\S]*)$/u.exec(changeset)?.groups;
		return parts !== undefined && /^"hephaestus":\s*major\s*$/mu.test(parts.front ?? "")
			? [(parts.summary ?? "").trim()]
			: [];
	});
}

/** Refuses a cut whose CHANGELOG.md or MIGRATION.md keeps a burned release section or lacks a published one. */
async function withReleaseHistory(
	plan: ReleasePlan,
	releases: readonly ReleaseRef[],
): Promise<ReleasePlan> {
	if (plan.kind !== "cut") {
		return plan;
	}
	const show = async (file: string) =>
		output("git", ["show", `${plan.sha}:${file}`], { env: environmentWithoutGitRepository() });
	const published = new Set(
		releases.flatMap((release) =>
			release.isDraft || !release.tag.startsWith("v") ? [] : [release.tag.slice(1)],
		),
	);
	const problems = releaseHistoryProblems(
		await show("CHANGELOG.md"),
		await show("MIGRATION.md"),
		published,
		plan.version,
	);
	return problems.length === 0
		? plan
		: {
				kind: "refuse",
				reason: `${problems.join("; ")}. A Version PR regenerated after the last release folds such a section into it; merge a change with a changeset so that main regenerates the Version PR, merge that, and cut again.`,
			};
}

/** Every release, drafts included — which the listing carries only for a token with push access. */
export async function listReleases(repository: string): Promise<ReleaseRef[]> {
	const listed = await output("gh", [
		"api",
		"--paginate",
		`repos/${repository}/releases?per_page=100`,
		// Projected rather than slurped whole: the release bodies are the changelog, and the full
		// listing already outgrows the default subprocess buffer.
		"--jq",
		".[] | {tag: .tag_name, draft: .draft, prerelease: .prerelease, immutable: .immutable, commitish: .target_commitish}",
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
				...(typeof release.immutable === "boolean" ? { isImmutable: release.immutable } : {}),
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

/** The changesets at `sha` that no Version PR consumed yet. */
export async function pendingChangesetFiles(sha: string, cwd?: string): Promise<string[]> {
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
		.filter((file) => /^\.changeset\/[^/]+\.md$/u.test(file) && file !== ".changeset/README.md");
}

/** Pending release notes belong to the validated source commit, not the current checkout. */
export async function hasPendingChangesets(sha: string, cwd?: string): Promise<boolean> {
	const files = await pendingChangesetFiles(sha, cwd);
	return files.length > 0;
}

/**
 * Why a merge group must not merge its Version PR, or `undefined` when it may. A Version PR
 * versions the changesets of the commit it was made from. Changesets that merged ahead of it in the
 * queue stay unconsumed at its commit, so a release cut there would ship changes without their
 * notes. The group fails, the Version PR leaves the queue, and maintenance versions it again.
 */
export function staleVersionPullRequest(
	source: Pick<VersionSource, "sha">,
	pending: readonly string[],
): string | undefined {
	return pending.length === 0
		? undefined
		: `the Version PR at ${source.sha} leaves ${pending.length} changesets unconsumed that merged after it was versioned (${pending.join(", ")}); maintenance versions it again on main, then queue it again`;
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

export async function verifyCandidateSource(
	repository: string,
	candidate: string,
	sha: string,
): Promise<ReleaseRef[]> {
	const releases = await listReleases(repository);
	assertPromotionCandidate(sha, candidate, releases);
	if ((await readTagCommit(repository, candidate)) !== sha) {
		throw new Error("The candidate tag does not name this source commit");
	}
	const mainOutput = await output("gh", [
		"api",
		`repos/${repository}/commits/main`,
		"--jq",
		".sha",
	]);
	const main = mainOutput.trim();
	if (main !== sha) {
		throw new Error("Main advanced after the candidate; qualify another RC before promotion");
	}
	return releases;
}

if (import.meta.main && process.argv[2] === "--aliases") {
	for (const alias of imageAliases(process.argv[3] ?? "")) {
		console.log(alias);
	}
} else if (import.meta.main && process.argv[2] === "--candidate-source") {
	const [, candidate, sha] = process.argv.slice(2);
	const repository = process.env.GITHUB_REPOSITORY;
	if (!isSet(candidate) || !isSet(sha) || !isSet(repository)) {
		throw new Error("usage: plan-release --candidate-source <candidate-tag> <sha>");
	}
	await verifyCandidateSource(repository, candidate, sha);
} else if (import.meta.main && process.argv[2] === "--promote") {
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
	const [, previousVersion, version, changelog, notes, releaseVersion] = process.argv.slice(2);
	if (!isSet(previousVersion) || !isSet(version) || !isSet(changelog) || !isSet(notes)) {
		console.log(
			"::error::usage: plan-release.ts --notes <previous-version> <version> <CHANGELOG.md> <notes-file> [<release-version>]",
		);
		process.exitCode = 1;
	} else {
		try {
			await appendFile(
				notes,
				await renderReleaseNotes(
					await readFile(changelog, "utf8"),
					previousVersion,
					version,
					releaseVersion === undefined || releaseVersion === version ? undefined : releaseVersion,
					releaseVersion === undefined || releaseVersion === version
						? []
						: majorChangesetSummaries(await consumedChangesets(path.dirname(changelog))),
				),
			);
		} catch (error) {
			console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
			process.exitCode = 1;
		}
	}
} else if (import.meta.main && process.argv[2] === "--consumed") {
	// A merge group that changes the version: its version commit must consume every changeset.
	const [, sha] = process.argv.slice(2);
	if (isSet(sha)) {
		try {
			const source = await findVersionSource(sha);
			const reason =
				source === undefined
					? `no first-parent commit of ${sha} sets its version`
					: staleVersionPullRequest(source, await pendingChangesetFiles(source.sha));
			if (reason === undefined) {
				console.log(`The version commit ${source?.sha ?? sha} consumes every changeset.`);
			} else {
				console.log(`::error::${reason}`);
				process.exitCode = 1;
			}
		} catch (error) {
			console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
			process.exitCode = 1;
		}
	} else {
		console.log("::error::usage: plan-release.ts --consumed <sha>");
		process.exitCode = 1;
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
	const promotion = process.argv[2] === "--candidate";
	const candidate = promotion ? process.argv[3] : undefined;
	const sha = process.argv[promotion ? 4 : 2];
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
		const version = asString(manifest.version, `${sha}:package.json version`);
		const pending = await hasPendingChangesets(sha);
		let plan: ReleasePlan;
		if (promotion) {
			if (!isSet(candidate) || candidate !== `v${version}`) {
				throw new Error("Promotion must select the exact candidate version on main");
			}
			plan = planCandidatePromotion(
				sha,
				candidate,
				await verifyCandidateSource(repository, candidate, sha),
				pending,
			);
		} else {
			const releases = await listReleases(repository);
			const published = releases.some(
				(release) => release.tag === `v${version}` && !release.isDraft,
			);
			try {
				const source = pending && !published ? await versionSource(repository, sha) : undefined;
				plan = planVersionRelease(sha, version, releases, pending, source);
			} catch (error) {
				plan = {
					kind: "refuse",
					reason: `could not read the commit that set ${version}: ${error instanceof Error ? error.message : String(error)}`,
				};
			}
			plan = await withReleaseHistory(plan, releases);
		}
		if (plan.kind === "refuse") {
			console.log(`::error::${plan.reason}`);
			process.exitCode = 1;
		} else {
			const migrations =
				plan.kind === "cut" && (await hasSchemaMigrations(`v${plan.previousVersion}`, plan.sha));
			console.log(
				plan.kind === "cut"
					? `Cutting ${plan.tag} at ${plan.sha}${plan.sha === sha ? "" : ` (the commit that set its version; ${sha} added changesets after it)`} after v${plan.previousVersion}${plan.resumesDraft ? ", resuming its draft" : ""}`
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
