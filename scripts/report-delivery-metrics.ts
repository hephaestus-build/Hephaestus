/**
 * Reports the four delivery metrics from GitHub's own records: merged pull requests, published
 * releases and the deployments that `promote.yml` records for each environment. Commit order comes
 * from the local first-parent history of the default branch.
 */
import { appendFile, mkdir, writeFile } from "node:fs/promises";

import { asArray, asRecord, asString, at, isRecord, parseJson, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { median, percentile } from "./lib/statistics.ts";
import { versionBranch } from "./report-ci-latency.ts";

const DAY = 24 * 60 * 60 * 1000;
export const ENVIRONMENTS = ["Staging", "Production"] as const;
type Environment = (typeof ENVIRONMENTS)[number];

export interface Change {
	number: number;
	title: string;
	firstCommitAt: number;
	openedAt: number;
	mergedAt: number;
	mergeCommit: string;
	/** The Version PR consumes changesets; it is release mechanics, not a change. */
	versionPr: boolean;
}

export interface Deployment {
	environment: Environment;
	sha: string;
	createdAt: number;
	/** The last of success, failure or error; a later `inactive` only means it was replaced. */
	outcome: "success" | "failure" | "error" | undefined;
	finishedAt: number | undefined;
	rollback: boolean;
}

export interface Release {
	tag: string;
	commit: string;
	publishedAt: number;
}

export interface Window {
	since: number;
	until: number;
}

/** Durations in milliseconds as count, median and p90; `null` when nothing was measured. */
function distribution(values: readonly number[]) {
	return { count: values.length, p50: median(values), p90: percentile(values, 0.9) };
}

const inWindow = (time: number, { since, until }: Window) => time >= since && time < until;

/** The first time something at or after `position` on the default branch reached a destination. */
function firstArrival(
	position: number,
	after: number,
	arrivals: readonly { position: number | undefined; at: number }[],
): number | undefined {
	let first: number | undefined;
	for (const arrival of arrivals) {
		if (arrival.position !== undefined && arrival.position >= position && arrival.at >= after) {
			first = first === undefined ? arrival.at : Math.min(first, arrival.at);
		}
	}
	return first;
}

/** A `vX.Y.Z` release with Z above 0: the cheap stand-in for rework, a release that fixes a release. */
export function isPatch(tag: string): boolean {
	return /^v\d+\.\d+\.[1-9]\d*$/u.test(tag);
}

/** GitHub's revert button and the Conventional Commit `revert` type. */
export function isRevert(title: string): boolean {
	return /^(?:Revert "|revert(?:\([^)]*\))?:)/u.test(title);
}

/**
 * A deployment failed when its record says so, or when the next deployment to the same environment
 * deliberately moved it back.
 */
function failures(deployments: readonly Deployment[]): Set<Deployment> {
	const failed = new Set<Deployment>();
	const ordered = deployments.toSorted((left, right) => left.createdAt - right.createdAt);
	for (const [index, deployment] of ordered.entries()) {
		if (deployment.outcome === "failure" || deployment.outcome === "error") {
			failed.add(deployment);
		}
		const next = ordered[index + 1];
		if (deployment.outcome === "success" && next?.rollback === true) {
			failed.add(deployment);
		}
	}
	return failed;
}

export function deliveryMetrics(
	window: Window,
	changes: readonly Change[],
	deployments: readonly Deployment[],
	releases: readonly Release[],
	position: ReadonlyMap<string, number>,
) {
	const arrivals = (environment: Environment) =>
		deployments
			.filter((deployment) => deployment.environment === environment)
			.filter((deployment) => deployment.outcome === "success")
			.map((deployment) => ({
				position: position.get(deployment.sha),
				at: deployment.finishedAt ?? deployment.createdAt,
			}));
	const staging = arrivals("Staging");
	const production = arrivals("Production");
	const released = releases.map((release) => ({
		position: position.get(release.commit),
		at: release.publishedAt,
	}));

	const merged = changes.filter((change) => inWindow(change.mergedAt, window) && !change.versionPr);
	const commitToMerge: number[] = [];
	const mergeToStaging: number[] = [];
	const mergeToRelease: number[] = [];
	const mergeToProduction: number[] = [];
	const commitToStaging: number[] = [];
	const commitToProduction: number[] = [];
	const openToProduction: number[] = [];
	let unplaced = 0;
	let awaitingStaging = 0;
	let awaitingRelease = 0;
	let awaitingProduction = 0;
	for (const change of merged) {
		commitToMerge.push(change.mergedAt - change.firstCommitAt);
		const place = position.get(change.mergeCommit);
		if (place === undefined) {
			unplaced += 1;
			continue;
		}
		const onStaging = firstArrival(place, change.mergedAt, staging);
		const inRelease = firstArrival(place, change.mergedAt, released);
		const onProduction = firstArrival(place, change.mergedAt, production);
		if (onStaging === undefined) {
			awaitingStaging += 1;
		} else {
			mergeToStaging.push(onStaging - change.mergedAt);
			commitToStaging.push(onStaging - change.firstCommitAt);
		}
		if (inRelease === undefined) {
			awaitingRelease += 1;
		} else {
			mergeToRelease.push(inRelease - change.mergedAt);
		}
		if (onProduction === undefined) {
			awaitingProduction += 1;
		} else {
			mergeToProduction.push(onProduction - change.mergedAt);
			commitToProduction.push(onProduction - change.firstCommitAt);
			openToProduction.push(onProduction - change.openedAt);
		}
	}

	const environments = ENVIRONMENTS.map((environment) => {
		const own = deployments.filter((deployment) => deployment.environment === environment);
		const failed = failures(own);
		const decided = own.filter(
			(deployment) => deployment.outcome !== undefined && inWindow(deployment.createdAt, window),
		);
		const successes = decided.filter(
			(deployment) => deployment.outcome === "success" && !failed.has(deployment),
		);
		const restores: number[] = [];
		let unrestored = 0;
		for (const deployment of decided.filter((candidate) => failed.has(candidate))) {
			const failedAt = deployment.finishedAt ?? deployment.createdAt;
			const restore = own
				.filter(
					(candidate) =>
						candidate.outcome === "success" &&
						!failed.has(candidate) &&
						candidate.createdAt > deployment.createdAt,
				)
				.map((candidate) => candidate.finishedAt ?? candidate.createdAt)
				.toSorted((left, right) => left - right)[0];
			if (restore === undefined) {
				unrestored += 1;
			} else {
				restores.push(restore - failedAt);
			}
		}
		const days = (window.until - window.since) / DAY;
		const earlier = own.filter((deployment) => deployment.createdAt < window.since);
		const seen = new Set(earlier.map((deployment) => deployment.sha));
		let repeats = 0;
		for (const deployment of decided.toSorted((left, right) => left.createdAt - right.createdAt)) {
			if (seen.has(deployment.sha)) {
				repeats += 1;
			}
			seen.add(deployment.sha);
		}
		return {
			environment,
			deployments: decided.length,
			repeats,
			successful: successes.length,
			perWeek: (successes.length / days) * 7,
			deploymentDays: new Set(
				successes.map((deployment) =>
					new Date(deployment.finishedAt ?? deployment.createdAt).toISOString().slice(0, 10),
				),
			).size,
			failed: decided.length - successes.length,
			changeFailureRate:
				decided.length === 0 ? null : (decided.length - successes.length) / decided.length,
			timeToRestore: distribution(restores),
			unrestored,
		};
	});

	const reverts = merged.filter((change) => isRevert(change.title)).length;
	const published = releases.filter((release) => inWindow(release.publishedAt, window));
	const patches = published.filter((release) => isPatch(release.tag)).length;
	return {
		window: {
			since: new Date(window.since).toISOString(),
			until: new Date(window.until).toISOString(),
		},
		changes: {
			merged: merged.length,
			reverts,
			revertRate: merged.length === 0 ? null : reverts / merged.length,
			unplaced,
			awaitingStaging,
			awaitingRelease,
			awaitingProduction,
		},
		releases: {
			published: published.length,
			patches,
			patchShare: published.length === 0 ? null : patches / published.length,
		},
		leadTime: {
			commitToMerge: distribution(commitToMerge),
			mergeToStaging: distribution(mergeToStaging),
			mergeToRelease: distribution(mergeToRelease),
			mergeToProduction: distribution(mergeToProduction),
			commitToStaging: distribution(commitToStaging),
			commitToProduction: distribution(commitToProduction),
			openToProduction: distribution(openToProduction),
		},
		environments,
	};
}

type Metrics = ReturnType<typeof deliveryMetrics>;

export function duration(milliseconds: number | null): string {
	if (milliseconds === null) {
		return "—";
	}
	const minutes = milliseconds / 60_000;
	if (minutes < 120) {
		return `${Math.round(minutes)} min`;
	}
	const hours = minutes / 60;
	return hours < 48 ? `${hours.toFixed(1)} h` : `${(hours / 24).toFixed(1)} d`;
}

const percent = (value: number | null) => (value === null ? "—" : `${Math.round(value * 100)} %`);

export function renderDeliveryMetrics(title: string, metrics: Metrics): string {
	const lead = (label: string, values: Metrics["leadTime"][keyof Metrics["leadTime"]]) =>
		`| ${label} | ${duration(values.p50)} | ${duration(values.p90)} | ${values.count} |`;
	return [
		`## ${title}`,
		"",
		`${metrics.window.since.slice(0, 10)} to ${metrics.window.until.slice(0, 10)}: ${metrics.changes.merged} changes merged, ${metrics.releases.published} releases published.`,
		"",
		"### Lead time for changes",
		"",
		"| From → to | Median | p90 | Changes |",
		"|---|---:|---:|---:|",
		lead("First commit → merged", metrics.leadTime.commitToMerge),
		lead("Merged → running on staging", metrics.leadTime.mergeToStaging),
		lead("Merged → in a published release", metrics.leadTime.mergeToRelease),
		lead("Merged → running in production", metrics.leadTime.mergeToProduction),
		lead("First commit → running on staging", metrics.leadTime.commitToStaging),
		lead("First commit → running in production", metrics.leadTime.commitToProduction),
		lead("Pull request opened → running in production", metrics.leadTime.openToProduction),
		"",
		`Not yet on staging: ${metrics.changes.awaitingStaging}. Not yet released: ${metrics.changes.awaitingRelease}. Not yet in production: ${metrics.changes.awaitingProduction}.`,
		...(metrics.changes.unplaced === 0
			? []
			: [
					"",
					`${metrics.changes.unplaced} merge commits are not on the default branch's first-parent history and are not measured.`,
				]),
		"",
		"### Deployments",
		"",
		"| Environment | Successful | Per week | Days with a deployment | Change failure rate | Time to restore (median) | Not restored | Repeats of a deployed build |",
		"|---|---:|---:|---:|---:|---:|---:|---:|",
		...metrics.environments.map(
			(environment) =>
				`| ${environment.environment} | ${environment.successful} | ${environment.perWeek.toFixed(1)} | ${environment.deploymentDays} | ${percent(environment.changeFailureRate)} (${environment.failed}/${environment.deployments}) | ${duration(environment.timeToRestore.p50)} (n=${environment.timeToRestore.count}) | ${environment.unrestored} | ${environment.repeats} |`,
		),
		"",
		`Reverted changes: ${metrics.changes.reverts} of ${metrics.changes.merged} (${percent(metrics.changes.revertRate)}).`,
		`Patch releases (rework): ${metrics.releases.patches} of ${metrics.releases.published} (${percent(metrics.releases.patchShare)}).`,
		"",
	].join("\n");
}

const time = (value: unknown, label: string) => {
	const parsed = Date.parse(asString(value, label));
	if (!Number.isFinite(parsed)) {
		throw new Error(`Invalid ${label}`);
	}
	return parsed;
};

export function parseChanges(pages: unknown, releaseBranch: string): Change[] {
	return asArray(pages, "pull request pages").flatMap((page) =>
		asArray(asRecord(at(page, ["data", "search"], "search"), "search").nodes, "pull requests").map(
			(value) => {
				const node = asRecord(value, "pull request");
				const firstCommit = asArray(asRecord(node.commits, "commits").nodes, "commit nodes")[0];
				const openedAt = time(node.createdAt, "createdAt");
				return {
					number: Number(node.number),
					title: asString(node.title, "title"),
					openedAt,
					firstCommitAt: isRecord(firstCommit)
						? time(asRecord(firstCommit.commit, "commit").authoredDate, "authoredDate")
						: openedAt,
					mergedAt: time(node.mergedAt, "mergedAt"),
					mergeCommit: asString(asRecord(node.mergeCommit, "mergeCommit").oid, "merge commit"),
					versionPr: node.headRefName === releaseBranch,
				};
			},
		),
	);
}

const PULLS_QUERY = `query($q: String!, $endCursor: String) {
  search(query: $q, type: ISSUE, first: 100, after: $endCursor) {
    pageInfo { hasNextPage endCursor }
    nodes { ... on PullRequest {
      number title createdAt mergedAt headRefName
      mergeCommit { oid }
      commits(first: 1) { nodes { commit { authoredDate } } }
    } }
  }
}`;

const RELEASES_QUERY = `query($owner: String!, $name: String!, $endCursor: String) {
  repository(owner: $owner, name: $name) {
    releases(first: 100, after: $endCursor, orderBy: { field: CREATED_AT, direction: DESC }) {
      pageInfo { hasNextPage endCursor }
      nodes { tagName publishedAt isDraft isPrerelease tagCommit { oid } }
    }
  }
}`;

export function parseReleases(pages: unknown): Release[] {
	return asArray(pages, "release pages").flatMap((page) =>
		asArray(
			asRecord(at(page, ["data", "repository", "releases"], "releases"), "releases").nodes,
			"releases",
		)
			.map((value) => asRecord(value, "release"))
			.filter(
				(node) =>
					node.isDraft === false &&
					node.isPrerelease === false &&
					typeof node.publishedAt === "string",
			)
			.map((node) => ({
				tag: asString(node.tagName, "tagName"),
				commit: asString(asRecord(node.tagCommit, "tagCommit").oid, "tag commit"),
				publishedAt: time(node.publishedAt, "publishedAt"),
			})),
	);
}

function isOutcome(state: unknown): state is NonNullable<Deployment["outcome"]> {
	return state === "success" || state === "failure" || state === "error";
}

/** The deployment's outcome from its statuses, which GitHub lists newest first. */
export function parseDeployment(
	environment: Environment,
	deployment: unknown,
	statuses: unknown,
): Deployment {
	const record = asRecord(deployment, "deployment");
	const decided = asArray(statuses, "statuses")
		.map((status) => asRecord(status, "status"))
		.find((status) => isOutcome(status.state));
	const payload = isRecord(record.payload) ? record.payload : {};
	return {
		environment,
		sha: asString(record.sha, "sha"),
		createdAt: time(record.created_at, "created_at"),
		outcome: isOutcome(decided?.state) ? decided.state : undefined,
		finishedAt: decided === undefined ? undefined : time(decided.created_at, "status created_at"),
		rollback: payload.allow_rollback === true,
	};
}

async function api(args: readonly string[]): Promise<unknown> {
	return parseJson(await output("gh", ["api", ...args]));
}

if (import.meta.main) {
	const repository = process.env.GITHUB_REPOSITORY;
	if (repository === undefined || !/^[\w.-]+\/[\w.-]+$/u.test(repository)) {
		throw new Error("GITHUB_REPOSITORY must be owner/repository");
	}
	const [owner = "", name = ""] = repository.split("/");
	const until = Date.now();
	const windows = [7, 28].map((days) => ({ days, since: until - days * DAY, until }));
	const since = Math.min(...windows.map((window) => window.since));
	const sinceDate = new Date(since).toISOString().slice(0, 10);

	const firstParents = await output("git", ["rev-list", "--first-parent", "origin/main"]);
	const history = firstParents.trim().split("\n");
	const position = new Map(history.map((sha, index) => [sha, history.length - index]));

	const changes = parseChanges(
		await api([
			"graphql",
			"--paginate",
			"--slurp",
			"-f",
			`query=${PULLS_QUERY}`,
			"-f",
			`q=repo:${repository} is:pr is:merged base:main merged:>=${sinceDate}`,
		]),
		versionBranch(await readJsonFile(".changeset/config.json")),
	);
	const releases = parseReleases([
		await api([
			"graphql",
			"-f",
			`query=${RELEASES_QUERY}`,
			"-f",
			`owner=${owner}`,
			"-f",
			`name=${name}`,
		]),
	]);
	const deployments: Deployment[] = [];
	for (const environment of ENVIRONMENTS) {
		const listed = asArray(
			await api([
				"--paginate",
				"--slurp",
				`repos/${repository}/deployments?environment=${environment}&per_page=100`,
			]),
			"deployment pages",
		).flatMap((page) => asArray(page, "deployments"));
		for (const deployment of listed) {
			const record = asRecord(deployment, "deployment");
			if (time(record.created_at, "created_at") < since) {
				continue;
			}
			const statuses = await api([
				`repos/${repository}/deployments/${String(record.id)}/statuses?per_page=100`,
			]);
			deployments.push(parseDeployment(environment, record, statuses));
		}
	}

	const reports = windows.map((window) => ({
		days: window.days,
		metrics: deliveryMetrics(window, changes, deployments, releases, position),
	}));
	await mkdir("tmp/delivery-metrics", { recursive: true });
	await writeFile(
		"tmp/delivery-metrics/delivery-metrics.json",
		`${JSON.stringify({ observedAt: new Date(until).toISOString(), reports }, null, 2)}\n`,
	);
	const rendered = reports
		.map(({ days, metrics }) =>
			renderDeliveryMetrics(`Delivery metrics, last ${days} days`, metrics),
		)
		.join("\n");
	process.stdout.write(rendered);
	if (process.env.GITHUB_STEP_SUMMARY !== undefined) {
		await appendFile(process.env.GITHUB_STEP_SUMMARY, rendered);
	}
}
