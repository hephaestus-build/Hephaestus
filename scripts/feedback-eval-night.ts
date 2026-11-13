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
	/** When the night ran, as an ISO timestamp. */
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

/** The last night whose panel measured and passed, from the newest cache entry under the prefix. */
export async function lastGreen(
	github: GitHub,
	repo: Repo,
): Promise<{ sha: string; date: string } | null> {
	const response = record(
		await github.rest.actions.getActionsCacheList({
			...repo,
			key: LAST_GREEN_PREFIX,
			sort: "created_at",
			direction: "desc",
			per_page: 10,
		}),
	);
	const caches = record(response.data).actions_caches;
	for (const entry of Array.isArray(caches) ? caches.map(record) : []) {
		const sha = typeof entry.key === "string" ? GREEN_KEY.exec(entry.key)?.groups?.sha : undefined;
		if (sha !== undefined && typeof entry.created_at === "string") {
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
	if (green === null) {
		const since = new Date(Date.parse(night.date) - CACHE_LIFETIME_DAYS * 86_400_000)
			.toISOString()
			.slice(0, 10);
		return `Last measured green night unknown (cache expired); range from ${since} not computed. This night ran on \`${night.sha}\`.`;
	}
	const compare = `${night.serverUrl}/${repo.owner}/${repo.repo}/compare`;
	return `Commits since the last night that measured and passed (${green.date.slice(0, 10)}): ${compare}/${green.sha}...${night.sha}`;
}

/**
 * Keeps the one tracking issue in step with a night: a night that measured and passed closes it, and any other night
 * comments on it, or opens it, with the commits since the last night that measured and passed. A replay of an attempt
 * that the issue already holds adds nothing.
 */
export async function reportNight(
	github: GitHub,
	repo: Repo,
	night: Night,
): Promise<"closed" | "commented" | "created" | "unchanged"> {
	const issues = await github.paginate(github.rest.issues.listForRepo, {
		...repo,
		creator: "github-actions[bot]",
		state: "open",
		per_page: 100,
	});
	const open = issues
		.map(record)
		.find((issue) => issue.pull_request === undefined && issue.title === ISSUE_TITLE);
	const number = typeof open?.number === "number" ? open.number : null;
	if (night.result === "success" && night.status === "passed") {
		if (number === null) {
			return "unchanged";
		}
		await github.rest.issues.createComment({
			...repo,
			issue_number: number,
			body: `The panel measured and passed again on \`${night.sha}\`: ${night.runUrl}`,
		});
		await github.rest.issues.update({ ...repo, issue_number: number, state: "closed" });
		return "closed";
	}
	const marker = `<!-- run:${night.runId}:${night.attempt} -->`;
	if (number !== null) {
		const comments = await github.paginate(github.rest.issues.listComments, {
			...repo,
			issue_number: number,
			per_page: 100,
		});
		const told = [open, ...comments.map(record)].some(
			(entry) =>
				typeof entry?.body === "string" &&
				entry.body.includes(marker) &&
				(entry === open || record(entry.user).login === "github-actions[bot]"),
		);
		if (told) {
			return "unchanged";
		}
	}
	const body = [
		marker,
		`@hephaestus-build/maintainer: the nightly feedback eval panel ${WHAT[night.status] ?? `did not finish (${night.result})`}.`,
		"",
		await range(github, repo, night),
		`Run: ${night.runUrl}`,
		`Result: ${await artifactOf(github, repo, night)}`,
	].join("\n");
	if (number !== null) {
		await github.rest.issues.createComment({ ...repo, issue_number: number, body });
		return "commented";
	}
	await github.rest.issues.create({ ...repo, title: ISSUE_TITLE, body });
	return "created";
}
