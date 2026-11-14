/**
 * A host verifies a release with the tooling of the release before it: it adopts new tooling only
 * after the apply (scripts/reconcile-deployment.ts). So an older verifier must accept this release,
 * and only that release's own code can show it. This runs prepare-release-chart.ts of the releases
 * that hosts run, from a tree with no installed packages, against the signed draft of this release.
 *
 * This job holds write tokens, so a pushed tag must never name the code that runs here. Only a
 * published immutable release counts: its tag cannot move, and its code passed a release.
 */
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { releaseRepository } from "./lib/release-identities.ts";
import { compareVersions, parseVersion } from "./lib/release-version.ts";
import { listReleases } from "./plan-release.ts";
import { chartRepository } from "./release-chart.ts";
import {
	chartVersion,
	jointLockFromEvidence,
	type ReleaseImageLockV2,
} from "./release-image-lock.ts";

const versionOf = (tag: string) => (tag.startsWith("v") ? parseVersion(tag.slice(1)) : undefined);

interface Published {
	tag: string;
	isDraft: boolean;
	isPrerelease: boolean;
	isImmutable?: boolean;
}

/**
 * Tooling below this release refuses every chart with another image set, so a host on it follows
 * the manual step in docs/admin/upgrade-to-1-0.mdx, and it proves nothing about a later release.
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

/** The program and arguments that an earlier verifier's tree runs against this release. */
type VerifierCommand = (verifier: string, tree: string) => Promise<string[]>;

async function checkVerifier(
	verifier: string,
	release: string,
	command: VerifierCommand,
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
	try {
		await run("git", ["worktree", "add", "--quiet", "--detach", tree, verifier]);
		try {
			await run(process.execPath, await command(verifier, tree));
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

type ListReleases = () => Promise<readonly Published[]>;
type AcceptedBreaks = () => Promise<readonly AcceptedBreak[]>;
interface Outcome {
	verifiers: string[];
	breaks: (AcceptedBreak & { failure: string })[];
}

const listPublished =
	(release: string): ListReleases =>
	async () =>
		listReleases(releaseRepository(release, process.env));
const readAcceptedBreaks: AcceptedBreaks = async () =>
	parseAcceptedBreaks(await readJsonFile(fileURLToPath(acceptedBreaksFile)));

async function checkWith(
	release: string,
	command: VerifierCommand,
	list: ListReleases,
	run: Run,
	accepted: AcceptedBreaks,
): Promise<Outcome> {
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
			await checkVerifier(verifier, release, command, run);
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

/** The publication gate: each verifier prepares the signed draft as a host would. */
export async function checkPreviousVerifiers(
	release: string,
	commit: string,
	destination: string,
	inventory: string,
	list: ListReleases = listPublished(release),
	run: Run = output,
	accepted: AcceptedBreaks = readAcceptedBreaks,
): Promise<Outcome> {
	return checkWith(
		release,
		async (verifier, tree) => {
			const chart = path.join(destination, verifier);
			await mkdir(chart, { recursive: true });
			return [
				path.join(tree, "scripts/prepare-release-chart.ts"),
				release,
				commit,
				chart,
				"helm",
				inventory,
			];
		},
		list,
		run,
		accepted,
	);
}

/**
 * The same gate before a release exists: the Version PR's release evidence preflight. The same
 * verifiers and accepted breaks decide, through scripts/previous-verifier-probe.ts, which skips only
 * the steps that need the signed draft. A break thus fails the Version PR instead of the release.
 */
export async function checkPreviousVerifiersBeforeRelease(
	release: string,
	lock: string,
	manifest: string,
	inventory: string,
	list: ListReleases = listPublished(release),
	run: Run = output,
	accepted: AcceptedBreaks = readAcceptedBreaks,
): Promise<Outcome> {
	const probe = fileURLToPath(new URL("previous-verifier-probe.ts", import.meta.url));
	return checkWith(
		release,
		async (_verifier, tree) => [
			probe,
			tree,
			release,
			path.resolve(lock),
			path.resolve(manifest),
			path.resolve(inventory),
		],
		list,
		run,
		accepted,
	);
}

/**
 * The joint lock that this release would sign, from its evidence manifest. Its chart has the
 * repository and version that the release pushes; the digest exists only after that push, so it is
 * zeros here.
 */
export function preflightJointLock(evidence: unknown, release: string): ReleaseImageLockV2 {
	return jointLockFromEvidence(
		evidence,
		{
			repository: chartRepository(release),
			version: chartVersion(release),
			digest: `sha256:${"0".repeat(64)}`,
		},
		release,
	);
}

function report(release: string, { verifiers, breaks }: Outcome): void {
	for (const { verifier, reason, failure } of breaks) {
		process.stdout.write(
			`::warning::Accepted: ${verifier} on ${release}: ${failure} (${reason})\n`,
		);
	}
	process.stdout.write(`Checked ${release} with the verifiers of ${verifiers.join(" and ")}\n`);
}

if (import.meta.main && process.argv[2] === "--preflight") {
	const [release = "", manifest = "", inventory = ""] = process.argv.slice(3);
	if ([release, manifest, inventory].includes("")) {
		throw new Error(
			"usage: check-previous-verifier --preflight <release> <manifest.json> <inventory.json>",
		);
	}
	const scratch = await mkdtemp(path.join(tmpdir(), "preflight-lock-"));
	try {
		const lock = path.join(scratch, "lock.json");
		const joint = preflightJointLock(await readJsonFile(manifest), release);
		await writeFile(lock, `${JSON.stringify(joint, null, 2)}\n`);
		report(release, await checkPreviousVerifiersBeforeRelease(release, lock, manifest, inventory));
	} finally {
		await rm(scratch, { recursive: true, force: true });
	}
} else if (import.meta.main) {
	const [release = "", commit = "", destination = "", inventory = ""] = process.argv.slice(2);
	report(release, await checkPreviousVerifiers(release, commit, destination, inventory));
}
