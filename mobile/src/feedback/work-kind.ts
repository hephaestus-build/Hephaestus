import type { ReviewedWorkRef } from "@/api/types.gen";
import type { IconName } from "@/ui/Icon";

type Provider = ReviewedWorkRef["provider"];

interface Words {
	one: string;
	many: string;
}

const CHANGE_REQUEST: Record<"GITHUB" | "GITLAB" | "other", Words> = {
	GITHUB: { one: "pull request", many: "pull requests" },
	GITLAB: { one: "merge request", many: "merge requests" },
	other: { one: "pull or merge request", many: "pull or merge requests" },
};

const OTHER_KINDS: Record<string, Words> = {
	"scm.issue": { one: "issue", many: "issues" },
	"chat.conversation_thread": { one: "conversation", many: "conversations" },
	"docs.document": { one: "document", many: "documents" },
};

/**
 * Kinds of reviewed work are an open vocabulary on the wire (`<domain>.<kind>`). The ones this build
 * knows are named in the provider's own word — a GitLab workspace has merge requests — and an unknown
 * kind keeps its raw id rather than disappearing.
 */
export function workLabel(kind: string, provider: Provider, count = 1): string {
	const words =
		kind === "scm.pull_request"
			? CHANGE_REQUEST[provider === "GITHUB" || provider === "GITLAB" ? provider : "other"]
			: OTHER_KINDS[kind];
	if (words === undefined) {
		return kind;
	}
	return count === 1 ? words.one : words.many;
}

const ICONS: Record<string, IconName> = {
	"scm.pull_request": { ios: "arrow.triangle.pull", android: "merge_type" },
	"scm.issue": { ios: "smallcircle.filled.circle", android: "adjust" },
	"chat.conversation_thread": { ios: "bubble.left.and.bubble.right", android: "forum" },
};

export function workIcon(kind: string): IconName {
	return ICONS[kind] ?? { ios: "doc.text", android: "description" };
}

/** "Seen on 3 pull requests" — counted per kind, in running text. */
export function seenOn(
	work: Pick<ReviewedWorkRef, "kind" | "provider">[],
	fallback: Provider,
): string {
	const counts = new Map<string, { kind: string; provider: Provider; count: number }>();
	for (const piece of work) {
		const provider = piece.provider ?? fallback;
		const key = `${piece.kind}:${provider}`;
		counts.set(key, { kind: piece.kind, provider, count: (counts.get(key)?.count ?? 0) + 1 });
	}
	const parts = [...counts.values()].map(
		({ kind, provider, count }) => `${count} ${workLabel(kind, provider, count)}`,
	);
	return parts.length === 0 ? "" : `Seen on ${parts.join(" and ")}`;
}
