/**
 * Reports the DORA delivery metrics from GitHub's own records: merged pull requests, published
 * releases and the deployments that `promote.yml` records for each environment. Commit order comes
 * from the local first-parent history of the default branch.
 */
import { appendFile, mkdir, writeFile } from "node:fs/promises";

import { asArray, asRecord, asString, at, isRecord, parseJson, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { median, percentile } from "./lib/statistics.ts";

/** The branch changesets maintains the Version PR on, from its configured base branch. */
export function versionBranch(config: unknown): string {
	const baseBranch = asString(asRecord(config, "changeset config").baseBranch, "baseBranch");
	if (!baseBranch) {
		throw new Error("changeset config declares no baseBranch");
	}
	return `changeset-release/${baseBranch}`;
}

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
	/** The stages of the last push: CI/CD started on it, its gate passed, and the last queue entry. */
	pushedAt: number | undefined;
	greenAt: number | undefined;
	queuedAt: number | undefined;
	/** How often the pull request entered the merge queue; more than once means it was dropped. */
	queueEntries: number;
}

export interface Deployment {
	environment: Environment;
	sha: string;
	createdAt: number;
	/** The last of success, failure or error; a later `inactive` only means it was replaced. */
	outcome: "success" | "failure" | "error" | undefined;
	finishedAt: number | undefined;
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

/** GitHub's revert button and the Conventional Commit `revert` type. */
export function isRevert(title: string): boolean {
	return /^(?:Revert "|revert(?:\([^)]*\))?:)/u.test(title);
}

export type Verdict = "success" | "failed" | "repeat" | "undecided";

/**
 * Walks one environment's deployments in order. A deployment failed when its record says so, or when
 * the next deployment that moved the environment went back on main's history; a failure counts
 * whatever it deployed. A successful or undecided deployment of what already runs is a repeat: it
 * moves nothing, so it is neither a deployment nor a failure.
 */
export function classify(
	deployments: readonly Deployment[],
	position: ReadonlyMap<string, number>,
): Map<Deployment, Verdict> {
	const verdicts = new Map<Deployment, Verdict>();
	const ordered = deployments.toSorted((left, right) => left.createdAt - right.createdAt);
	let running: string | undefined;
	let previous: Deployment | undefined;
	for (const deployment of ordered) {
		// A failure counts whatever it deployed. Afterwards, what runs is unknown, so the next deployment
		// that stays is a recovery: it neither repeats nor rolls back what ran before.
		if (deployment.outcome === "failure" || deployment.outcome === "error") {
			verdicts.set(deployment, "failed");
			running = undefined;
			previous = undefined;
			continue;
		}
		if (deployment.sha === running) {
			verdicts.set(deployment, "repeat");
			continue;
		}
		if (deployment.outcome === undefined) {
			verdicts.set(deployment, "undecided");
			continue;
		}
		const from = previous === undefined ? undefined : position.get(previous.sha);
		const to = position.get(deployment.sha);
		if (previous !== undefined && from !== undefined && to !== undefined && to < from) {
			verdicts.set(previous, "failed");
		}
		verdicts.set(deployment, "success");
		running = deployment.sha;
		previous = deployment;
	}
	return verdicts;
}

const doneAt = (deployment: Deployment) => deployment.finishedAt ?? deployment.createdAt;

export function deliveryMetrics(
	window: Window,
	changes: readonly Change[],
	deployments: readonly Deployment[],
	releases: readonly Release[],
	position: ReadonlyMap<string, number>,
) {
	const verdicts = new Map(
		ENVIRONMENTS.flatMap((environment) => [
			...classify(
				deployments.filter((deployment) => deployment.environment === environment),
				position,
			),
		]),
	);
	const verdict = (deployment: Deployment) => verdicts.get(deployment) ?? "undecided";
	// Only a deployment that stayed counts as the change running there: a rolled-back one did not.
	const arrivals = (environment: Environment) =>
		deployments
			.filter(
				(deployment) => deployment.environment === environment && verdict(deployment) === "success",
			)
			.map((deployment) => ({ position: position.get(deployment.sha), at: doneAt(deployment) }));
	const staging = arrivals("Staging");
	const production = arrivals("Production");
	const released = releases.map((release) => ({
		position: position.get(release.commit),
		at: release.publishedAt,
	}));

	const merged = changes.filter((change) => inWindow(change.mergedAt, window) && !change.versionPr);
	const pushToGreen: number[] = [];
	const greenToQueued: number[] = [];
	const queuedToMerged: number[] = [];
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
		const { pushedAt, greenAt, queuedAt } = change;
		if (pushedAt !== undefined && greenAt !== undefined && greenAt >= pushedAt) {
			pushToGreen.push(greenAt - pushedAt);
		}
		if (greenAt !== undefined && queuedAt !== undefined) {
			// Auto-merge can queue a pull request the moment its gate passes.
			greenToQueued.push(Math.max(0, queuedAt - greenAt));
		}
		if (queuedAt !== undefined) {
			queuedToMerged.push(change.mergedAt - queuedAt);
		}
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
		const own = deployments
			.filter((deployment) => deployment.environment === environment)
			.toSorted((left, right) => left.createdAt - right.createdAt);
		const current = own.filter((deployment) => inWindow(deployment.createdAt, window));
		const decided = current.filter(
			(deployment) => verdict(deployment) === "success" || verdict(deployment) === "failed",
		);
		const successes = decided.filter((deployment) => verdict(deployment) === "success");
		// Consecutive failures are one incident, recovered by the next deployment that stayed.
		const recoveries: number[] = [];
		let incident: number | undefined;
		let unrecovered = 0;
		for (const deployment of own) {
			if (verdict(deployment) === "failed" && incident === undefined) {
				incident = doneAt(deployment);
			} else if (verdict(deployment) === "success" && incident !== undefined) {
				if (inWindow(incident, window)) {
					recoveries.push(doneAt(deployment) - incident);
				}
				incident = undefined;
			}
		}
		if (incident !== undefined && inWindow(incident, window)) {
			unrecovered = 1;
		}
		const days = (window.until - window.since) / DAY;
		return {
			environment,
			deployments: decided.length,
			repeats: current.filter((deployment) => verdict(deployment) === "repeat").length,
			successful: successes.length,
			perWeek: (successes.length / days) * 7,
			deploymentDays: new Set(
				successes.map((deployment) => new Date(doneAt(deployment)).toISOString().slice(0, 10)),
			).size,
			failed: decided.length - successes.length,
			changeFailRate:
				decided.length === 0 ? null : (decided.length - successes.length) / decided.length,
			recoveryTime: distribution(recoveries),
			unrecovered,
			// A deployment that ended an incident was made to fix one, not planned.
			reworkRate: decided.length === 0 ? null : recoveries.length / decided.length,
			unplaced: current.filter((deployment) => !position.has(deployment.sha)).length,
			undecided: current.filter((deployment) => deployment.outcome === undefined).length,
		};
	});

	const reverts = merged.filter((change) => isRevert(change.title)).length;
	const published = releases.filter((release) => inWindow(release.publishedAt, window));
	return {
		window: {
			since: new Date(window.since).toISOString(),
			until: new Date(window.until).toISOString(),
		},
		changes: {
			merged: merged.length,
			reverts,
			revertRate: merged.length === 0 ? null : reverts / merged.length,
			requeued: merged.filter((change) => change.queueEntries > 1).length,
			unplaced,
			awaitingStaging,
			awaitingRelease,
			awaitingProduction,
		},
		releases: published.length,
		unplacedReleases: published.filter((release) => !position.has(release.commit)).length,
		leadTime: {
			pushToGreen: distribution(pushToGreen),
			greenToQueued: distribution(greenToQueued),
			queuedToMerged: distribution(queuedToMerged),
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

/** What the first-parent history of the default branch cannot place, so the report cannot measure. */
function unplacedLines(metrics: Metrics): string[] {
	const counts = [
		[metrics.changes.unplaced, "merge commits"],
		[metrics.unplacedReleases, "release commits"],
		...metrics.environments.map(
			({ environment, unplaced }) => [unplaced, `${environment} deployments`] as const,
		),
	] as const;
	return counts
		.filter(([count]) => count > 0)
		.map(
			([count, what]) =>
				`${count} ${what} are not on the default branch's first-parent history and are not measured.`,
		)
		.flatMap((line) => ["", line]);
}

export function renderDeliveryMetrics(title: string, metrics: Metrics): string {
	const lead = (label: string, values: Metrics["leadTime"][keyof Metrics["leadTime"]]) =>
		`| ${label} | ${duration(values.p50)} | ${duration(values.p90)} | ${values.count} |`;
	return [
		`## ${title}`,
		"",
		`${metrics.window.since.slice(0, 10)} to ${metrics.window.until.slice(0, 10)}: ${metrics.changes.merged} changes merged, ${metrics.releases} releases published.`,
		"",
		"### Lead time for changes",
		"",
		"| From → to | Median | p90 | Changes |",
		"|---|---:|---:|---:|",
		lead("Last push → pull-request CI passed", metrics.leadTime.pushToGreen),
		lead("CI passed → entered the merge queue", metrics.leadTime.greenToQueued),
		lead("Entered the merge queue → merged", metrics.leadTime.queuedToMerged),
		lead("First commit → merged", metrics.leadTime.commitToMerge),
		lead("Merged → running on staging", metrics.leadTime.mergeToStaging),
		lead("Merged → in a published release", metrics.leadTime.mergeToRelease),
		lead("Merged → running in production", metrics.leadTime.mergeToProduction),
		lead("First commit → running on staging", metrics.leadTime.commitToStaging),
		lead("First commit → running in production", metrics.leadTime.commitToProduction),
		lead("Pull request opened → running in production", metrics.leadTime.openToProduction),
		"",
		`Dropped from the merge queue at least once: ${metrics.changes.requeued} of ${metrics.changes.merged}.`,
		"",
		`Not yet on staging: ${metrics.changes.awaitingStaging}. Not yet released: ${metrics.changes.awaitingRelease}. Not yet in production: ${metrics.changes.awaitingProduction}.`,
		...unplacedLines(metrics),
		"",
		"### Deployments",
		"",
		"Production is the DORA measure; Staging shows the flow before it. The fail rate, recovery time and rework rate are proxies: a failure is a deployment whose version check failed or that was rolled back, and rework is a deployment that ended such an incident. See the Delivery Metrics page.",
		"",
		"| Environment | Successful | Per week | Days with a deployment | Change fail rate (proxy) | Failed deployment recovery time (median, proxy) | Not recovered | Deployment rework rate (proxy) | Repeats of what ran |",
		"|---|---:|---:|---:|---:|---:|---:|---:|---:|",
		...metrics.environments.map(
			(environment) =>
				`| ${environment.environment} | ${environment.successful} | ${environment.perWeek.toFixed(1)} | ${environment.deploymentDays} | ${percent(environment.changeFailRate)} (${environment.failed}/${environment.deployments}) | ${duration(environment.recoveryTime.p50)} (n=${environment.recoveryTime.count}) | ${environment.unrecovered} | ${percent(environment.reworkRate)} | ${environment.repeats} |`,
		),
		"",
		`Reverted changes: ${metrics.changes.reverts} of ${metrics.changes.merged} (${percent(metrics.changes.revertRate)}).`,
		...metrics.environments
			.filter(({ undecided }) => undecided > 0)
			.map(
				({ environment, undecided }) =>
					`${undecided} ${environment} deployments have no final status yet and are not counted.`,
			),
		"",
	].join("\n");
}

function positiveInteger(value: unknown, label: string): number {
	if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) {
		throw new TypeError(`Invalid ${label}`);
	}
	return value;
}

const time = (value: unknown, label: string) => {
	const parsed = Date.parse(asString(value, label));
	if (!Number.isFinite(parsed)) {
		throw new Error(`Invalid ${label}`);
	}
	return parsed;
};

const earliest = (times: readonly number[]) =>
	times.length === 0 ? undefined : Math.min(...times);
const latest = (times: readonly number[]) => (times.length === 0 ? undefined : Math.max(...times));

/** When the merged head was pushed, passed CI (the gate's `All CI Passed` status), and last entered the merge queue. */
function pushStages(node: Record<string, unknown>) {
	const head = asArray(asRecord(node.head, "head").nodes, "head commits")[0];
	const commit = isRecord(head) ? asRecord(head.commit, "head commit") : {};
	const contexts = isRecord(commit.status)
		? asArray(commit.status.contexts, "status contexts")
		: [];
	const suites = isRecord(commit.checkSuites)
		? asArray(commit.checkSuites.nodes, "check suites")
		: [];
	if (
		isRecord(commit.checkSuites) &&
		at(commit.checkSuites, ["pageInfo", "hasNextPage"], "check suites") === true
	) {
		throw new Error(
			`pull request ${String(node.number)} has more check suites than one page lists`,
		);
	}
	const entries = asArray(asRecord(node.timelineItems, "timeline").nodes, "queue entries").map(
		(entry) => time(asRecord(entry, "queue entry").createdAt, "queue entry createdAt"),
	);
	return {
		pushedAt: earliest(
			suites.flatMap((suite) => {
				const run = isRecord(suite) ? suite.workflowRun : undefined;
				return isRecord(run) &&
					run.event === "pull_request" &&
					isRecord(run.workflow) &&
					run.workflow.name === "CI/CD"
					? [time(run.createdAt, "workflow run createdAt")]
					: [];
			}),
		),
		greenAt: latest(
			contexts.flatMap((value) => {
				const status = asRecord(value, "status context");
				return status.context === "All CI Passed" && status.state === "SUCCESS"
					? [time(status.createdAt, "status createdAt")]
					: [];
			}),
		),
		queuedAt: latest(entries),
		queueEntries: entries.length,
	};
}

export function parseChanges(pages: unknown, releaseBranch: string): Change[] {
	return asArray(pages, "pull request pages").flatMap((page) =>
		asArray(asRecord(at(page, ["data", "search"], "search"), "search").nodes, "pull requests").map(
			(value) => {
				const node = asRecord(value, "pull request");
				const firstCommit = asArray(asRecord(node.commits, "commits").nodes, "commit nodes")[0];
				const openedAt = time(node.createdAt, "createdAt");
				return {
					number: positiveInteger(node.number, "pull request number"),
					title: asString(node.title, "title"),
					openedAt,
					firstCommitAt: isRecord(firstCommit)
						? time(asRecord(firstCommit.commit, "commit").authoredDate, "authoredDate")
						: openedAt,
					mergedAt: time(node.mergedAt, "mergedAt"),
					mergeCommit: asString(asRecord(node.mergeCommit, "mergeCommit").oid, "merge commit"),
					versionPr: node.headRefName === releaseBranch,
					...pushStages(node),
				};
			},
		),
	);
}

const PULLS_QUERY = `query($q: String!, $endCursor: String) {
  search(query: $q, type: ISSUE, first: 100, after: $endCursor) {
    issueCount
    pageInfo { hasNextPage endCursor }
    nodes { ... on PullRequest {
      number title createdAt mergedAt headRefName
      mergeCommit { oid }
      commits(first: 1) { nodes { commit { authoredDate } } }
      head: commits(last: 1) { nodes { commit {
        status { contexts { context state createdAt } }
        checkSuites(first: 50) {
          pageInfo { hasNextPage }
          nodes { workflowRun { event createdAt workflow { name } } }
        }
      } } }
      timelineItems(itemTypes: [ADDED_TO_MERGE_QUEUE_EVENT], last: 20) {
        nodes { ... on AddedToMergeQueueEvent { createdAt } }
      }
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

/** The number of results the search found, which can exceed what it returns. */
export function searchCount(pages: unknown): number {
	const [first] = asArray(pages, "pull request pages");
	const count = asRecord(at(first, ["data", "search"], "search"), "search").issueCount;
	if (typeof count !== "number") {
		throw new TypeError("search returned no issueCount");
	}
	return count;
}

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
	return {
		environment,
		sha: asString(record.sha, "sha"),
		createdAt: time(record.created_at, "created_at"),
		outcome: isOutcome(decided?.state) ? decided.state : undefined,
		finishedAt: decided === undefined ? undefined : time(decided.created_at, "status created_at"),
	};
}

async function api(args: readonly string[]): Promise<unknown> {
	return parseJson(await output("gh", ["api", ...args]));
}

/** GitHub lists deployments newest first, so the pages stop at the first one before `since`. */
async function deploymentsSince(
	repository: string,
	environment: Environment,
	since: number,
): Promise<Record<string, unknown>[]> {
	const found: Record<string, unknown>[] = [];
	for (let page = 1; ; page += 1) {
		const listed = asArray(
			await api([
				`repos/${repository}/deployments?environment=${environment}&per_page=100&page=${page}`,
			]),
			"deployments",
		).map((value) => asRecord(value, "deployment"));
		const recent = listed.filter((record) => time(record.created_at, "created_at") >= since);
		found.push(...recent);
		if (listed.length < 100 || recent.length < listed.length) {
			return found;
		}
	}
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

	const pulls = await api([
		"graphql",
		"--paginate",
		"--slurp",
		"-f",
		`query=${PULLS_QUERY}`,
		"-f",
		`q=repo:${repository} is:pr is:merged base:main merged:>=${sinceDate}`,
	]);
	const changes = parseChanges(pulls, versionBranch(await readJsonFile(".changeset/config.json")));
	if (changes.length < searchCount(pulls)) {
		// GitHub's search returns at most 1,000 results.
		throw new Error(
			`only ${changes.length} of ${searchCount(pulls)} merged pull requests were listed`,
		);
	}
	const releases = parseReleases(
		await api([
			"graphql",
			"--paginate",
			"--slurp",
			"-f",
			`query=${RELEASES_QUERY}`,
			"-f",
			`owner=${owner}`,
			"-f",
			`name=${name}`,
		]),
	);
	const deployments: Deployment[] = [];
	for (const environment of ENVIRONMENTS) {
		const records = await deploymentsSince(repository, environment, since);
		// A few requests at a time: one per deployment, without a burst against the API.
		for (let start = 0; start < records.length; start += 8) {
			deployments.push(
				...(await Promise.all(
					records
						.slice(start, start + 8)
						.map(async (record) =>
							parseDeployment(
								environment,
								record,
								await api([
									`repos/${repository}/deployments/${String(record.id)}/statuses?per_page=100`,
								]),
							),
						),
				)),
			);
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
