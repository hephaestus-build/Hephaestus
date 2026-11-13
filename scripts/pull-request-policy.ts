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
