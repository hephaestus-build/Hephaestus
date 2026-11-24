/**
 * The trusted half of the pull request policy. `pull-request.yml` runs on `pull_request` with no
 * permissions and records which pull request it ran for. `pull-request-policy.yml` runs on
 * `workflow_run` from the default branch and imports this module from there, so a pull request never
 * supplies its own policy. The record is untrusted data: it only names the pull request to read
 * from the API, and only while that pull request's head is the commit that the recording run tested.
 * Policy: docs/contributor/ci-cd.mdx#pull-request-policy.
 */

/** The pull request as the API returns it, reduced to what the policy reads. */
interface PullRequest {
	readonly number: number;
	readonly draft?: boolean;
	readonly state: string;
	readonly title: string;
	readonly head: { readonly sha: string };
	readonly base: { readonly ref: string };
	readonly user: { readonly login: string; readonly type: string } | null;
}

interface PullsApi {
	readonly rest: {
		readonly pulls: {
			readonly get: (params: Record<string, unknown>) => Promise<{ data: PullRequest }>;
		};
	};
}

/** What the trusted jobs act on. */
export interface ResolvedPullRequest {
	readonly number: number;
	readonly draft: boolean;
	readonly base: string;
	readonly author: string;
	readonly authorIsUser: boolean;
	/** The recording run saw the pull request opened, so its author is assigned. */
	readonly opened: boolean;
}

/** The actions that `pull-request.yml` runs on. */
const ACTIONS = new Set([
	"opened",
	"edited",
	"synchronize",
	"reopened",
	"ready_for_review",
	"stacked",
]);

/**
 * The pull request number and event action that the recording run wrote, or `undefined` when the
 * record is not exactly a number and a known action. Nothing else of the record is read.
 */
export const parseRecord = (text: string): { number: number; action: string } | undefined => {
	const match = /^(?<number>[1-9]\d{0,8})\n(?<action>[a-z_]{1,32})\n?$/u.exec(text);
	if (match?.groups === undefined) {
		return undefined;
	}
	const { number, action } = match.groups;
	if (number === undefined || action === undefined || !ACTIONS.has(action)) {
		return undefined;
	}
	return { number: Number(number), action };
};

/**
 * Reads the recorded pull request from the API. Returns `undefined` when the record is malformed,
 * the pull request is closed, or its head is no longer the commit that the recording run tested:
 * a newer run then carries the verdict for the newer head.
 */
export const resolvePullRequest = async ({
	github,
	repo,
	record,
	headSha,
}: {
	readonly github: PullsApi;
	readonly repo: { readonly owner: string; readonly repo: string };
	readonly record: string;
	readonly headSha: string;
}): Promise<ResolvedPullRequest | undefined> => {
	const parsed = parseRecord(record);
	if (parsed === undefined || !/^[0-9a-f]{40}$/u.test(headSha)) {
		return undefined;
	}
	const { data: pull } = await github.rest.pulls.get({ ...repo, pull_number: parsed.number });
	if (pull.state !== "open" || pull.head.sha !== headSha || pull.user === null) {
		return undefined;
	}
	return {
		number: pull.number,
		draft: pull.draft === true,
		base: pull.base.ref,
		author: pull.user.login,
		authorIsUser: pull.user.type === "User",
		opened: parsed.action === "opened",
	};
};

/** The pull request title policy that `commitlint.config.ts` exports. */
export interface TitlePolicy {
	readonly types: string;
	readonly scopes: string;
	readonly headerMaxLength: number;
	readonly headerPattern: string;
	readonly subjectPattern: string;
	readonly breakingMarkerPattern: string;
	readonly retiredNamePattern: string;
	readonly retiredNameMessage: string;
}

/** Why `title` breaks the title policy, or an empty string when it does not. */
export const titleProblem = (title: string, policy: TitlePolicy): string => {
	if (title.length > policy.headerMaxLength) {
		return `The title is ${title.length} characters long; the limit is ${policy.headerMaxLength}.`;
	}
	if (/[\r\n]/u.test(title)) {
		return "The title must be a single line.";
	}
	if (new RegExp(policy.breakingMarkerPattern, "u").test(title)) {
		return "A breaking change is carried by a changeset, so the title takes no ! marker.";
	}
	if (new RegExp(policy.retiredNamePattern, "u").test(title)) {
		return policy.retiredNameMessage;
	}
	const header = new RegExp(policy.headerPattern, "u").exec(title);
	if (header === null) {
		return "The title must have the form `type(scope): description`, or `type: description`.";
	}
	const [, type = "", scope, subject = ""] = header;
	if (!policy.types.split("\n").includes(type)) {
		return `The type \`${type}\` is not allowed.`;
	}
	if (scope !== undefined && !policy.scopes.split("\n").includes(scope)) {
		return `The scope \`${scope}\` is not allowed.`;
	}
	if (!new RegExp(policy.subjectPattern, "u").test(subject)) {
		return "The description must not be empty or end with a period.";
	}
	return "";
};

/** The pull request as the plan-link rule reads it from the API. */
export interface PlannedPullRequest {
	readonly body: string | null;
	readonly head: { readonly ref: string; readonly repo: { readonly full_name: string } | null };
	readonly base: { readonly ref: string };
}

interface IssuesApi {
	readonly rest: {
		readonly issues: {
			readonly get: (
				params: Record<string, unknown>,
			) => Promise<{ data: { readonly body?: string | null; readonly pull_request?: unknown } }>;
		};
	};
}

/** The line that marks work that no 1.0 issue covers. */
export const OUTSIDE_PLAN = "Outside the 1.0 plan:";

/** The filing tool writes this marker into the body of every issue on the 1.0 board. */
const BOARD_MARKER = /<!--\s*heph-key:\s*[\w.-]+\s*-->/u;

/** A description names at most this many issues for the rule to read; the rest are ignored. */
const MAX_REFERENCES = 20;

/**
 * The author's own prose in a description. HTML comments, fenced and inline code, and quoted lines
 * are removed: a template comment, an example or a quote names an issue without advancing it.
 */
export const authoredProse = (body: string): string => {
	const lines: string[] = [];
	let fence: string | undefined;
	// An unclosed comment hides the rest of the description, as GitHub renders it.
	for (const line of body.replaceAll(/<!--[\s\S]*?(?:-->|$)/gu, "").split(/\r?\n/u)) {
		const marker = /^ {0,3}(?<marker>`{3,}|~{3,})/u.exec(line)?.groups?.marker;
		if (fence !== undefined) {
			if (
				marker !== undefined &&
				marker.startsWith(fence.charAt(0)) &&
				marker.length >= fence.length
			) {
				fence = undefined;
			}
		} else if (marker !== undefined) {
			fence = marker;
		} else if (!/^ {0,3}>/u.test(line)) {
			lines.push(line.replaceAll(/(?<ticks>`+)[^`]*?\k<ticks>/gu, ""));
		}
	}
	return lines.join("\n");
};

/**
 * The issue numbers that the description closes or advances, in order and without repeats:
 * `Closes #n` with any of GitHub's closing keywords, or `Part of #n`, in any case.
 */
export const planReferences = (body: string): number[] => {
	const pattern =
		/(?<![\w-])(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?|part\s+of)(?:\s*:\s*|\s+)#(?<number>[1-9]\d{0,8})(?![\w-])/giu;
	const numbers = [...authoredProse(body).matchAll(pattern)].map((match) =>
		Number(match.groups?.number),
	);
	return [...new Set(numbers)];
};

/**
 * The reason on the description's `Outside the 1.0 plan:` line: `undefined` without the line, and
 * an empty string when the line gives no reason.
 */
export const outsidePlanReason = (body: string): string | undefined => {
	const reasons = authoredProse(body)
		.split("\n")
		.map((line) => line.replace(/^\s*(?:[-*+]\s+)?/u, ""))
		.filter(
			(line) => line.slice(0, OUTSIDE_PLAN.length).toLowerCase() === OUTSIDE_PLAN.toLowerCase(),
		)
		.map((line) => line.slice(OUTSIDE_PLAN.length).trim());
	if (reasons.length === 0) {
		return undefined;
	}
	return reasons.find((reason) => /[\p{L}\p{N}]/u.test(reason)) ?? "";
};

/**
 * Renovate's pull requests and the Version PR write no prose, so they name no issue. Only a branch
 * of this repository counts: a fork can name its branch anything.
 */
export const isAutomation = (
	pull: PlannedPullRequest,
	repo: { readonly owner: string; readonly repo: string },
): boolean =>
	pull.head.repo?.full_name.toLowerCase() === `${repo.owner}/${repo.repo}`.toLowerCase() &&
	(pull.head.ref.startsWith("renovate/") || pull.head.ref === `changeset-release/${pull.base.ref}`);

/** An issue, not a pull request, whose body carries the 1.0 board's marker. */
const isOnBoard = async (
	github: IssuesApi,
	repo: { readonly owner: string; readonly repo: string },
	number: number,
): Promise<boolean> => {
	try {
		const { data } = await github.rest.issues.get({ ...repo, issue_number: number });
		return data.pull_request === undefined && BOARD_MARKER.test(data.body ?? "");
	} catch (error) {
		// Octokit's request error carries the HTTP status: a deleted or unknown number is no 1.0 issue.
		const status =
			typeof error === "object" && error !== null && "status" in error ? error.status : undefined;
		if (status === 404 || status === 410) {
			return false;
		}
		throw error;
	}
};

/**
 * Why the pull request names neither a 1.0 issue nor a reason to be outside the 1.0 plan, or an
 * empty string when it does. The description and the issue bodies are untrusted data: they are only
 * matched, never run.
 */
export const planLinkProblem = async ({
	github,
	repo,
	pull,
}: {
	readonly github: IssuesApi;
	readonly repo: { readonly owner: string; readonly repo: string };
	readonly pull: PlannedPullRequest;
}): Promise<string> => {
	if (isAutomation(pull, repo)) {
		return "";
	}
	const body = pull.body ?? "";
	const references = planReferences(body).slice(0, MAX_REFERENCES);
	for (const number of references) {
		if (await isOnBoard(github, repo, number)) {
			return "";
		}
	}
	const reason = outsidePlanReason(body);
	if (reason !== undefined && reason !== "") {
		return "";
	}
	const problems = [];
	if (references.length > 0) {
		const listed = references.map((number) => `#${number}`).join(", ");
		problems.push(`No issue that the description references (${listed}) is on the 1.0 board.`);
	}
	if (reason === "") {
		problems.push(`The \`${OUTSIDE_PLAN}\` line gives no reason.`);
	}
	if (problems.length === 0) {
		problems.push(
			"The description names no 1.0 issue and does not say why the work is outside the 1.0 plan.",
		);
	}
	return problems.join(" ");
};
