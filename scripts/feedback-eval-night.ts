/**
 * The tracking issue of the nightly feedback eval panel: docs/contributor/feedback-quality-evals.md § A skipped panel.
 * The panel's own report job and the `CI / Scheduled failures` fallback both import this one implementation, from an
 * `actions/github-script` step. It loads no package: those jobs install nothing.
 */

export const PANEL_WORKFLOW = "CI / Feedback eval panel";
export const ISSUE_TITLE = "The nightly feedback eval panel is not green";
const WORKFLOW_FILE = "feedback-eval-panel.yml";

type Route = (parameters: Record<string, unknown>) => Promise<unknown>;

/** The part of the github-script Octokit client that the report calls. */
export interface GitHub {
	paginate: ((route: Route, parameters: Record<string, unknown>) => Promise<unknown[]>) & {
		iterator: (
			route: Route,
			parameters: Record<string, unknown>,
		) => AsyncIterable<{ data: unknown }>;
	};
	rest: {
		issues: { listForRepo: Route; create: Route; createComment: Route; update: Route };
		actions: {
			listWorkflowRuns: Route;
			listJobsForWorkflowRun: Route;
			listWorkflowRunArtifacts: Route;
		};
	};
}

export interface Repo {
	owner: string;
	repo: string;
}

/** One scheduled run of the panel, as its report or the fallback sees it. */
export interface Night {
	runId: number;
	sha: string;
	runUrl: string;
	serverUrl: string;
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

/**
 * The commit of the newest earlier night whose panel measured and passed: its "Model panel" job succeeded. A night
 * without a provider succeeds with a skipped panel, so a successful run alone is not a green night. The search pages
 * through every successful scheduled run of the panel.
 */
export async function lastMeasuredGreen(
	github: GitHub,
	repo: Repo,
	currentRunId: number,
): Promise<string | null> {
	const pages = github.paginate.iterator(github.rest.actions.listWorkflowRuns, {
		...repo,
		workflow_id: WORKFLOW_FILE,
		event: "schedule",
		status: "success",
		per_page: 100,
	});
	for await (const page of pages) {
		for (const run of Array.isArray(page.data) ? page.data.map(record) : []) {
			if (run.id === currentRunId || typeof run.head_sha !== "string") {
				continue;
			}
			const jobs = await github.paginate(github.rest.actions.listJobsForWorkflowRun, {
				...repo,
				run_id: run.id,
				per_page: 100,
			});
			if (
				jobs.map(record).some((job) => job.name === "Model panel" && job.conclusion === "success")
			) {
				return run.head_sha;
			}
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

/**
 * Keeps the one tracking issue in step with a night: a night that measured and passed closes it, and any other night
 * comments on it, or opens it, with the commits since the last night that measured and passed.
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
	const green = await lastMeasuredGreen(github, repo, night.runId);
	const compare = `${night.serverUrl}/${repo.owner}/${repo.repo}/compare`;
	const body = [
		`@hephaestus-build/maintainer: the nightly feedback eval panel ${WHAT[night.status] ?? `did not finish (${night.result})`}.`,
		"",
		green === null
			? `No night has measured and passed yet. This night ran on \`${night.sha}\`.`
			: `Commits since the last night that measured and passed: ${compare}/${green}...${night.sha}`,
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
