/**
 * The tracking issue of the nightly feedback eval panel: docs/contributor/ci-cd.mdx § Feedback eval panel provider.
 * The panel's own report job and the `CI / Scheduled failures` fallback both import this one implementation, from an
 * `actions/github-script` step. It loads no package: those jobs install nothing.
 */

export const PANEL_WORKFLOW = "CI / Feedback eval panel";
export const ISSUE_TITLE = "The nightly feedback eval panel is not green";
/**
 * A night whose panel measured and passed saves an Actions cache entry under this prefix, with its run ID and commit
 * in the key. The newest entry is the last green night. GitHub evicts an entry that nobody used for 7 days.
 */
export const LAST_GREEN_PREFIX = "feedback-eval-last-green-";
const CACHE_LIFETIME_DAYS = 7;

type Route = (parameters: Record<string, unknown>) => Promise<unknown>;

/** The part of the github-script Octokit client that the report calls. */
export interface GitHub {
	paginate: (route: Route, parameters: Record<string, unknown>) => Promise<unknown[]>;
	rest: {
		issues: {
			listForRepo: Route;
			listComments: Route;
			create: Route;
			createComment: Route;
			update: Route;
		};
		actions: { getActionsCacheList: Route; listWorkflowRunArtifacts: Route };
	};
}

export interface Repo {
	owner: string;
	repo: string;
}

/** One scheduled run of the panel, as its report or the fallback sees it. */
export interface Night {
	runId: number;
	attempt: number;
	sha: string;
	runUrl: string;
	serverUrl: string;
	/** When this attempt of the run started, as an ISO timestamp. */
	date: string;
	/** The panel job's result, or the run's conclusion when the report job did not run. */
	result: string;
	/** The panel's status output: passed, failed, skipped or ceiling-reached. Empty when the job wrote none. */
	status: string;
	/** The kept result, when the caller knows it. Otherwise the report looks it up on the run. */
	artifactUrl: string;
}

const record = (value: unknown): Record<string, unknown> =>
	typeof value === "object" && value !== null ? Object.fromEntries(Object.entries(value)) : {};

const WHAT: Record<string, string> = {
	failed: "fell below a floor",
	skipped: "measured nothing, because the endpoint gave no composition for a panel case",
	"ceiling-reached": "stopped at its call ceiling",
};

const GREEN_KEY = new RegExp(`^${LAST_GREEN_PREFIX}\\d+-(?<sha>[0-9a-f]{40})$`, "u");

/** Masks what a message can carry that the job log must not show: URLs and long token-like strings. */
export const masked = (message: string) =>
	message
		.replaceAll(/[a-z][a-z0-9+.-]*:\/\/\S+/giu, "<url>")
		.replaceAll(/[\w-]{20,}/gu, "<redacted>");

/**
 * Names a failed API call for the issue: its HTTP status, or a fixed description by the error's class. An exception
 * message can hold an internal URL or a token, so it never reaches the issue. The job log gets it, masked.
 */
function lookupFailure(error: unknown): string {
	const { status } = record(error);
	if (typeof status === "number") {
		return `HTTP ${status}`;
	}
	const kind = error instanceof Error ? error.name : typeof error;
	console.error(
		`Feedback eval night lookup failed (${kind}): ${masked(error instanceof Error ? error.message : String(error))}`,
	);
	return `lookup failed: ${kind}`;
}

/** Issue timestamps come from another clock, so the duplicate check looks a little before the run started. */
const CLOCK_SKEW_MS = 5 * 60_000;

/** Only a scheduled night on the default branch saves the state, so only its entries count. */
const MAIN_REF = "refs/heads/main";

type Green = { sha: string; date: string } | { failure: string } | null;

/**
 * The last night whose panel measured and passed: the newest cache entry under the prefix on the default branch. A
 * failed lookup is returned as such, so that the report still goes out.
 */
export async function lastGreen(github: GitHub, repo: Repo): Promise<Green> {
	let response: Record<string, unknown>;
	try {
		response = record(
			await github.rest.actions.getActionsCacheList({
				...repo,
				key: LAST_GREEN_PREFIX,
				ref: MAIN_REF,
				sort: "created_at",
				direction: "desc",
				per_page: 10,
			}),
		);
	} catch (error) {
		return { failure: lookupFailure(error) };
	}
	const caches = record(response.data).actions_caches;
	for (const entry of Array.isArray(caches) ? caches.map(record) : []) {
		const sha = typeof entry.key === "string" ? GREEN_KEY.exec(entry.key)?.groups?.sha : undefined;
		if (sha !== undefined && entry.ref === MAIN_REF && typeof entry.created_at === "string") {
			return { sha, date: entry.created_at };
		}
	}
	return null;
}

async function artifactOf(github: GitHub, repo: Repo, night: Night): Promise<string> {
	if (night.artifactUrl !== "") {
		return night.artifactUrl;
	}
	const artifacts = await github.paginate(github.rest.actions.listWorkflowRunArtifacts, {
		...repo,
		run_id: night.runId,
		per_page: 100,
	});
	const kept = artifacts.map(record).find((artifact) => artifact.name === "feedback-eval-panel");
	return typeof kept?.id === "number"
		? `${night.runUrl}/artifacts/${kept.id}`
		: "no artifact was kept";
}

async function range(github: GitHub, repo: Repo, night: Night): Promise<string> {
	const green = await lastGreen(github, repo);
	if (green === null || "failure" in green) {
		const since = new Date(Date.parse(night.date) - CACHE_LIFETIME_DAYS * 86_400_000)
			.toISOString()
			.slice(0, 10);
		const why = green === null ? "cache expired" : `cache lookup failed: ${green.failure}`;
		return `Last measured green night unknown (${why}); range from ${since} not computed. This night ran on \`${night.sha}\`.`;
	}
	const compare = `${night.serverUrl}/${repo.owner}/${repo.repo}/compare`;
	return `Commits since the last night that measured and passed (${green.date.slice(0, 10)}): ${compare}/${green.sha}...${night.sha}`;
}

/** Whether an issue's body or one of its bot comments already holds the marker of this attempt. */
async function holds(
	github: GitHub,
	repo: Repo,
	issue: Record<string, unknown>,
	marker: string,
): Promise<boolean> {
	if (typeof issue.body === "string" && issue.body.includes(marker)) {
		return true;
	}
	const comments = await github.paginate(github.rest.issues.listComments, {
		...repo,
		issue_number: issue.number,
		per_page: 100,
	});
	return comments
		.map(record)
		.some(
			(comment) =>
				record(comment.user).login === "github-actions[bot]" &&
				typeof comment.body === "string" &&
				comment.body.includes(marker),
		);
}

/** The panel's tracking issues that the client lists for these parameters. */
async function panelIssues(github: GitHub, repo: Repo, parameters: Record<string, unknown>) {
	const listed = await github.paginate(github.rest.issues.listForRepo, {
		...repo,
		creator: "github-actions[bot]",
		per_page: 100,
		...parameters,
	});
	return listed
		.map(record)
		.filter((issue) => issue.pull_request === undefined && issue.title === ISSUE_TITLE);
}

/**
 * Whether a panel issue already holds this attempt's marker. Only this attempt writes its marker, after the run
 * started, so only issues updated since then are read. A failed lookup is returned as such: a duplicate is better than
 * silence.
 */
async function alreadyTold(
	github: GitHub,
	repo: Repo,
	night: Night,
	marker: string,
): Promise<boolean | { failure: string }> {
	try {
		const since = new Date(Date.parse(night.date) - CLOCK_SKEW_MS).toISOString();
		const recent = await panelIssues(github, repo, { state: "all", since });
		for (const issue of recent) {
			if (await holds(github, repo, issue, marker)) {
				return true;
			}
		}
		return false;
	} catch (error) {
		return { failure: lookupFailure(error) };
	}
}

const unchecked = (failure: string) =>
	`The duplicate check could not complete (${failure}), so this comment may repeat an earlier one.`;

/**
 * Keeps the one tracking issue in step with a night: a night that measured and passed closes it, and any other night
 * comments on it, or opens it, with the commits since the last night that measured and passed. Each comment carries the
 * attempt's marker, so a replay of an attempt that a panel issue already holds, open or closed, adds nothing.
 */
export async function reportNight(
	github: GitHub,
	repo: Repo,
	night: Night,
): Promise<"closed" | "commented" | "created" | "unchanged"> {
	const [open] = await panelIssues(github, repo, { state: "open" });
	const number = typeof open?.number === "number" ? open.number : null;
	const marker = `<!-- run:${night.runId}:${night.attempt} -->`;
	const told = await alreadyTold(github, repo, night, marker);
	if (told === true) {
		// A replay after a failed close finds its own comment and only closes.
		if (night.result === "success" && night.status === "passed" && number !== null) {
			await github.rest.issues.update({ ...repo, issue_number: number, state: "closed" });
			return "closed";
		}
		return "unchanged";
	}
	const note = told === false ? [] : ["", unchecked(told.failure)];
	if (night.result === "success" && night.status === "passed") {
		if (number === null) {
			return "unchanged";
		}
		await github.rest.issues.createComment({
			...repo,
			issue_number: number,
			body: [
				marker,
				`The panel measured and passed again on \`${night.sha}\`: ${night.runUrl}`,
				...note,
			].join("\n"),
		});
		await github.rest.issues.update({ ...repo, issue_number: number, state: "closed" });
		return "closed";
	}
	const body = [
		marker,
		`@hephaestus-build/maintainer: the nightly feedback eval panel ${WHAT[night.status] ?? `did not finish (${night.result})`}.`,
		"",
		await range(github, repo, night),
		`Run: ${night.runUrl}`,
		`Result: ${await artifactOf(github, repo, night)}`,
		...note,
	].join("\n");
	if (number !== null) {
		await github.rest.issues.createComment({ ...repo, issue_number: number, body });
		return "commented";
	}
	await github.rest.issues.create({ ...repo, title: ISSUE_TITLE, body });
	return "created";
}
