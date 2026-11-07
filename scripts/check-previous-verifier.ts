/**
 * A host verifies a release with the tooling of the release before it: it adopts new tooling only
 * after the apply (scripts/reconcile-deployment.ts). So an older verifier must accept this release,
 * and only that release's own code can show it. This runs prepare-release-chart.ts of the releases
 * that hosts run, from a tree with no installed packages, against the signed draft of this release.
 *
 * This job holds write tokens, so a pushed tag must never name the code that runs here. Only a
 * published immutable release counts: its tag cannot move, and its code passed a release.
 */
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { releaseRepository } from "./lib/release-identities.ts";
import { compareVersions, parseVersion } from "./lib/release-version.ts";
import { listReleases } from "./plan-release.ts";

const versionOf = (tag: string) => (tag.startsWith("v") ? parseVersion(tag.slice(1)) : undefined);

interface Published {
	tag: string;
	isDraft: boolean;
	isPrerelease: boolean;
	isImmutable?: boolean;
}

/**
 * Tooling below this release refuses every chart with another image set, so a host on it follows
 * the manual step in docs/admin/upgrade.mdx, and it proves nothing about a later release.
 */
const OLDEST_VERIFIER = "v1.0.0-rc.4";

/**
 * The verifiers to run: the nearest release below this one, and the latest stable one, or the oldest
 * verifier before a stable one exists. A host on a stable release never ran the candidates after it,
 * and one verifier accepting says nothing of another.
 */
type Version = NonNullable<ReturnType<typeof versionOf>>;

const isReachable = (verifier: Version, target: Version): boolean => {
	const oldest = versionOf(OLDEST_VERIFIER);
	return (
		oldest === undefined ||
		compareVersions(target, oldest) <= 0 ||
		compareVersions(verifier, oldest) >= 0
	);
};

export function verifiersOf(releases: readonly Published[], release: string): string[] {
	const target = versionOf(release);
	if (target === undefined) {
		throw new Error(`${release} is not a release tag`);
	}
	const below = releases
		.filter((candidate) => !candidate.isDraft && candidate.isImmutable === true)
		.flatMap((candidate) => {
			const version = versionOf(candidate.tag);
			return version === undefined ? [] : [{ ...candidate, version }];
		})
		.filter(({ version }) => compareVersions(version, target) < 0)
		.filter(({ version }) => isReachable(version, target))
		.toSorted((a, b) => compareVersions(b.version, a.version));
	const nearest = below[0]?.tag;
	// Until a stable release exists, the hosts that the manual step moved stay on the oldest verifier.
	const anchor =
		below.find((candidate) => !candidate.isPrerelease)?.tag ??
		below.find((candidate) => candidate.tag === OLDEST_VERIFIER)?.tag;
	return [...new Set([nearest, anchor].filter((tag) => tag !== undefined))];
}

type Run = (command: string, args: string[]) => Promise<string>;

/**
 * A planned break: this verifier may refuse this one release. It ships in the reviewed commit that
 * the release signs, and it names one pair, so it cannot reach a later release.
 */
export interface AcceptedBreak {
	release: string;
	verifier: string;
	reason: string;
}

const acceptedBreaksFile = new URL("../security/accepted-verifier-breaks.json", import.meta.url);

export function parseAcceptedBreaks(value: unknown): AcceptedBreak[] {
	return asArray(value, "accepted verifier breaks").map((entry, index) => {
		const label = `accepted verifier break ${String(index)}`;
		const record = asRecord(entry, label);
		const reason = asString(record.reason, `${label}.reason`);
		if (reason.trim() === "") {
			throw new Error(`${label} must state why the break is accepted and what a host does`);
		}
		return {
			release: asString(record.release, `${label}.release`),
			verifier: asString(record.verifier, `${label}.verifier`),
			reason,
		};
	});
}

async function checkVerifier(
	verifier: string,
	release: string,
	commit: string,
	destination: string,
	inventory: string,
	run: Run,
): Promise<void> {
	// The release job's checkout has one commit and no tags. In a full clone, a depth would make the
	// whole clone shallow, so fetch only a tag that is missing.
	const present = await run("git", ["rev-parse", "--quiet", "--verify", `refs/tags/${verifier}`])
		.then(() => true)
		.catch(() => false);
	if (!present) {
		await run("git", [
			"fetch",
			"--quiet",
			"--depth=1",
			"origin",
			`refs/tags/${verifier}:refs/tags/${verifier}`,
		]);
	}
	const scratch = await mkdtemp(path.join(tmpdir(), "previous-verifier-"));
	const tree = path.join(scratch, "tree");
	const chart = path.join(destination, verifier);
	try {
		await mkdir(chart, { recursive: true });
		await run("git", ["worktree", "add", "--quiet", "--detach", tree, verifier]);
		try {
			await run(process.execPath, [
				path.join(tree, "scripts/prepare-release-chart.ts"),
				release,
				commit,
				chart,
				"helm",
				inventory,
			]);
		} catch (error) {
			throw new Error(`the verifier of ${verifier} did not accept ${release}`, { cause: error });
		}
	} finally {
		await run("git", ["worktree", "remove", "--force", tree]).catch(() => "");
		await rm(scratch, { recursive: true, force: true });
	}
}

/** The line of a failure that names it: an older tool's own error, not "Command failed". */
function failureOf(error: unknown): string {
	const cause = error instanceof Error ? error.cause : undefined;
	const message = cause instanceof Error ? cause.message : String(error);
	const lines = message.split("\n").map((line) => line.trim());
	return lines.find((line) => line.startsWith("Error: ")) ?? lines[0] ?? "";
}

export async function checkPreviousVerifiers(
	release: string,
	commit: string,
	destination: string,
	inventory: string,
	list: () => Promise<readonly Published[]> = async () =>
		listReleases(releaseRepository(release, process.env)),
	run: Run = output,
	accepted: () => Promise<readonly AcceptedBreak[]> = async () =>
		parseAcceptedBreaks(await readJsonFile(fileURLToPath(acceptedBreaksFile))),
): Promise<{ verifiers: string[]; breaks: (AcceptedBreak & { failure: string })[] }> {
	const verifiers = verifiersOf(await list(), release);
	if (verifiers.length === 0) {
		throw new Error(
			`no published release comes before ${release}, so nothing proves a host accepts it`,
		);
	}
	const known = await accepted();
	const breaks: (AcceptedBreak & { failure: string })[] = [];
	for (const verifier of verifiers) {
		try {
			await checkVerifier(verifier, release, commit, destination, inventory, run);
		} catch (error) {
			const planned = known.find(
				(entry) => entry.release === release && entry.verifier === verifier,
			);
			if (planned === undefined) {
				throw error;
			}
			// A failure that is no refusal also ends here: the log states which one it was.
			breaks.push({ ...planned, failure: failureOf(error) });
		}
	}
	return { verifiers, breaks };
}

if (import.meta.main) {
	const [release = "", commit = "", destination = "", inventory = ""] = process.argv.slice(2);
	const { verifiers, breaks } = await checkPreviousVerifiers(
		release,
		commit,
		destination,
		inventory,
	);
	for (const { verifier, reason, failure } of breaks) {
		process.stdout.write(
			`::warning::Accepted: ${verifier} on ${release}: ${failure} (${reason})\n`,
		);
	}
	process.stdout.write(`Checked ${release} with the verifiers of ${verifiers.join(" and ")}\n`);
}
