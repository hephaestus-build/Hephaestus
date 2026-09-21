/**
 * Supplies the maintainer exception to GitHub's native review requirement.
 * Policy: docs/contributor/ci-cd.mdx#merge-policy.
 * Only the default branch's copy runs under pull_request_target; no pull-request code is executed.
 */

type ApiMethod<T> = (params: Record<string, unknown>) => Promise<{ data: T }>;

export interface Review {
	readonly id: number;
	readonly node_id: string;
	readonly state: string;
	readonly commit_id: string;
	readonly body?: string | null;
	readonly user: { readonly login: string } | null;
}

export interface PullRequest {
	readonly number: number;
	readonly head: { readonly sha: string };
	readonly user: { readonly login: string };
}

export interface CreateReviewRequest {
	readonly owner: string;
	readonly repo: string;
	readonly pull_number: number;
	readonly commit_id: string;
	readonly event: string;
	readonly body: string;
}

export interface GitHubApi {
	readonly graphql: (query: string, variables: { subjectId: string }) => Promise<unknown>;
	readonly paginate: (
		endpoint: ApiMethod<Review[]>,
		params: Record<string, unknown>,
	) => Promise<Review[]>;
	readonly rest: {
		readonly pulls: {
			readonly get: ApiMethod<PullRequest>;
			readonly listReviews: ApiMethod<Review[]>;
			readonly createReview: (
				params: CreateReviewRequest,
			) => Promise<{ data: { readonly id: number } }>;
		};
	};
}

export interface ActionsContext {
	readonly eventName: string;
	readonly repo: { readonly owner: string; readonly repo: string };
	readonly payload: {
		readonly pull_request?: { readonly number: number };
	};
}

export interface ActionsCore {
	readonly info: (message: string) => void;
	readonly warning: (message: string) => void;
	readonly setFailed: (message: string) => void;
}

export interface EnforceInput {
	readonly github: GitHubApi;
	readonly context: ActionsContext;
	readonly core: ActionsCore;
}

// Match the author as well as this marker: anyone can copy a review body.
export const APPROVAL_MARKER = "<!-- review-policy: maintainer auto-approval -->";

/** Review states that replace a reviewer's previous position. `COMMENTED` leaves it standing. */
const DECISIVE_STATES = new Set(["APPROVED", "CHANGES_REQUESTED", "DISMISSED"]);

const shortSha = (sha: string): string => sha.slice(0, 7);

export const parseMaintainers = (raw: string | undefined): Set<string> =>
	new Set(
		(raw ?? "")
			.split(",")
			.map((login) => login.trim().toLowerCase())
			.filter(Boolean),
	);

const decisiveBotReviews = (reviews: readonly Review[]): Review[] =>
	reviews
		.filter(
			(entry) => entry.user?.login === "github-actions[bot]" && DECISIVE_STATES.has(entry.state),
		)
		.toSorted((left, right) => left.id - right.id);

const ourReviews = (reviews: readonly Review[]): Review[] =>
	decisiveBotReviews(reviews).filter((entry) => (entry.body ?? "").includes(APPROVAL_MARKER));

export const approvalStands = (reviews: readonly Review[], headSha: string): boolean => {
	// A synchronize event can arrive before GitHub dismisses the old review.
	const latest = decisiveBotReviews(reviews).at(-1);
	return (
		latest !== undefined &&
		(latest.body ?? "").includes(APPROVAL_MARKER) &&
		latest.state === "APPROVED" &&
		latest.commit_id === headSha
	);
};

const minimizeReview = `mutation MinimizeReview($subjectId: ID!) {
	minimizeComment(input: { subjectId: $subjectId, classifier: OUTDATED }) {
		minimizedComment {
			isMinimized
		}
	}
}`;

const minimizeSupersededApprovals = async (
	github: GitHubApi,
	reviews: readonly Review[],
	core: ActionsCore,
): Promise<void> => {
	for (const review of reviews) {
		try {
			await github.graphql(minimizeReview, { subjectId: review.node_id });
		} catch (error) {
			const reason = error instanceof Error ? error.message : String(error);
			core.warning(`Could not minimize superseded automatic approval ${review.id}: ${reason}`);
		}
	}
};

export type DecisionKind = "approve" | "standing" | "skip";

export interface Decision {
	readonly kind: DecisionKind;
	readonly reason: string;
}

export interface DecisionInput {
	readonly author: string;
	readonly headSha: string;
	readonly maintainers: ReadonlySet<string>;
	readonly reviews: readonly Review[];
}

export const decide = (input: DecisionInput): Decision => {
	if (!input.maintainers.has(input.author.toLowerCase())) {
		return {
			kind: "skip",
			reason:
				`@${input.author} is not listed in REVIEW_POLICY_MAINTAINERS, so this pull request ` +
				"needs an approval from someone with write access.",
		};
	}

	if (approvalStands(input.reviews, input.headSha)) {
		return {
			kind: "standing",
			reason: `This pull request is already approved for ${shortSha(input.headSha)}.`,
		};
	}

	return {
		kind: "approve",
		reason:
			`@${input.author} is listed in REVIEW_POLICY_MAINTAINERS, so ${shortSha(input.headSha)} ` +
			"qualifies for automatic approval.",
	};
};

export const approvalBody = (author: string): string =>
	`${APPROVAL_MARKER}\nApproved automatically: @${author} is listed in the ` +
	"`REVIEW_POLICY_MAINTAINERS` repository variable, which the repository treats as satisfying the " +
	"review requirement. See the [review policy](https://docs.hephaestus.build/contributor/ci-cd#merge-policy).";

// Re-read the head: queued events may describe a commit that has already been replaced.
export const enforce = async ({ github, context, core }: EnforceInput): Promise<void> => {
	const eventPullRequest = context.payload.pull_request;
	if (!eventPullRequest) {
		core.setFailed(`A ${context.eventName} event carried no pull request to evaluate.`);
		return;
	}

	try {
		const { data: pull } = await github.rest.pulls.get({
			...context.repo,
			pull_number: eventPullRequest.number,
		});

		const maintainers = parseMaintainers(process.env.MAINTAINERS);
		if (maintainers.size === 0) {
			core.warning(
				"REVIEW_POLICY_MAINTAINERS is empty or unset, so no pull request is auto-approved. " +
					"Every pull request now needs an approval from someone with write access.",
			);
		}

		const reviews = await github.paginate(github.rest.pulls.listReviews, {
			...context.repo,
			pull_number: pull.number,
			per_page: 100,
		});

		const decision = decide({
			author: pull.user.login,
			headSha: pull.head.sha,
			maintainers,
			reviews,
		});
		core.info(decision.reason);
		if (decision.kind === "skip") {
			return;
		}
		if (decision.kind === "standing") {
			await minimizeSupersededApprovals(github, ourReviews(reviews).slice(0, -1), core);
			return;
		}

		// Pin the approval to the evaluated head even if a push races this request.
		await github.rest.pulls.createReview({
			...context.repo,
			pull_number: pull.number,
			commit_id: pull.head.sha,
			event: "APPROVE",
			body: approvalBody(pull.user.login),
		});
		await minimizeSupersededApprovals(github, ourReviews(reviews), core);
		core.info(`Approved ${shortSha(pull.head.sha)} on pull request #${pull.number}.`);
	} catch (error) {
		const reason = error instanceof Error ? error.message : String(error);
		core.setFailed(`The review policy could not be applied: ${reason}`);
	}
};
