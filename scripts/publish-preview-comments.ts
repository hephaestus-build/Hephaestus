import { asStringArray, readJsonFile } from "./lib/json.ts";
import { PREVIEW_COMMENT_LIMIT } from "./lib/preview-comment.ts";

type ApiMethod<T> = (params: Record<string, unknown>) => Promise<{ data: T }>;
interface Comment {
	id: number;
	body?: string | null;
	user: { login: string } | null;
}

interface GitHubApi {
	paginate: <T>(method: ApiMethod<T[]>, params: Record<string, unknown>) => Promise<T[]>;
	rest: {
		issues: {
			listComments: ApiMethod<Comment[]>;
			createComment: ApiMethod<unknown>;
			updateComment: ApiMethod<unknown>;
			deleteComment: ApiMethod<unknown>;
		};
	};
}

/**
 * One owner for publication and shrinking lists of the Storybook preview. Chromatic keeps each
 * build's Storybook, so a closed pull request keeps working links and needs no teardown.
 */
export async function publishPreviewComments({
	github,
	context,
	path,
}: {
	github: GitHubApi;
	context: {
		repo: { owner: string; repo: string };
		issue: { number: number };
	};
	path: string;
}): Promise<void> {
	const kind = "storybook";
	const bodies = asStringArray(await readJsonFile(path), "Preview comments");
	if (
		bodies.length === 0 ||
		bodies.some((body) => !body.trim() || body.length > PREVIEW_COMMENT_LIMIT)
	) {
		throw new Error("Preview comments must be nonempty and fit GitHub's comment limit.");
	}
	const { repo, issue } = context;
	const comments = await github.paginate(github.rest.issues.listComments, {
		...repo,
		issue_number: issue.number,
		per_page: 100,
	});
	const marker = (index: number) =>
		`<!-- Sticky Pull Request Comment${kind}-preview${index ? `-part-${index + 1}` : ""} -->`;
	const owned = comments.filter(
		(comment) =>
			comment.user?.login === "github-actions[bot]" &&
			new RegExp(
				`<!-- Sticky Pull Request Comment${kind}-preview(?:-part-(?:[2-9]|[1-9]\\d+))? -->$`,
				"u",
			).test(comment.body?.trimEnd() ?? ""),
	);
	const retained = new Set<number>();
	// Create in reading order and update in place; retries converge after a partial API failure.
	for (const [index, content] of bodies.entries()) {
		const body = `${content}\n${marker(index)}`;
		const previous = owned.find(
			(comment) => comment.body?.trimEnd().endsWith(marker(index)) === true,
		);
		if (previous) {
			retained.add(previous.id);
			if (previous.body !== body) {
				await github.rest.issues.updateComment({ ...repo, comment_id: previous.id, body });
			}
		} else {
			await github.rest.issues.createComment({ ...repo, issue_number: issue.number, body });
		}
	}
	// Delete only this publisher's stale parts, and only after all current parts were published.
	for (const comment of owned) {
		if (!retained.has(comment.id)) {
			await github.rest.issues.deleteComment({ ...repo, comment_id: comment.id });
		}
	}
}
