/**
 * Unsent messages to Heph, kept while the person moves around the app so a half-written question is
 * not lost. A draft belongs to one session, workspace and conversation; signing out or switching
 * workspace clears every draft, so nothing typed in one scope appears in another.
 */
const drafts = new Map<string, string>();

/** The session and workspace a draft belongs to. */
export interface DraftScope {
	epoch: number;
	workspaceSlug: string;
}

function scopeKey({ epoch, workspaceSlug }: DraftScope): string {
	return `${epoch}\u0000${workspaceSlug}\u0000`;
}

export function readDraft(scope: DraftScope, thread: string): string {
	return drafts.get(scopeKey(scope) + thread) ?? "";
}

/** Keeps `text` for this conversation and forgets drafts of every other scope. */
export function writeDraft(scope: DraftScope, thread: string, text: string): void {
	const prefix = scopeKey(scope);
	for (const existing of drafts.keys()) {
		if (!existing.startsWith(prefix)) {
			drafts.delete(existing);
		}
	}
	if (text === "") {
		drafts.delete(prefix + thread);
	} else {
		drafts.set(prefix + thread, text);
	}
}

export function clearDrafts(): void {
	drafts.clear();
}

/**
 * The opening a conversation about one piece of feedback starts from: it names the feedback so the
 * person does not have to re-explain it, and it is only a draft — nothing is sent until they send it.
 */
export function feedbackDraft(feedback: { headline: string; practiceName: string }): string {
	return `I got practice feedback on “${feedback.practiceName}”: “${feedback.headline}”. Can you help me understand what to change next time?`;
}

/**
 * What a conversation opens with: the draft kept for it, else one naming the feedback it was opened
 * from, else the starter question chosen, else nothing.
 */
export function openingDraft(
	kept: string,
	about: { headline: string; practiceName: string } | undefined,
	starter: string | undefined,
): string {
	if (kept !== "") {
		return kept;
	}
	if (about !== undefined) {
		return feedbackDraft(about);
	}
	const index = starter === undefined ? Number.NaN : Number(starter);
	return STARTERS[index] ?? "";
}

/** Questions to start from when there is nothing on the page yet; none claims anything about the person. */
export const STARTERS = [
	"How do I write a pull request description reviewers can act on?",
	"What makes a review comment easy to respond to?",
	"How should I split a large change into smaller ones?",
] as const;
