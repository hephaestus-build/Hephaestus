/**
 * Unsent text per composer, for this tab: switching to another thread and back restores what the
 * developer was writing there. Memory only; a reload starts empty.
 */
const drafts = new Map<string, string>();

export function readDraft(key: string): string {
	return drafts.get(key) ?? "";
}

export function writeDraft(key: string, text: string) {
	if (text === "") {
		drafts.delete(key);
	} else {
		drafts.set(key, text);
	}
}

/** Forgets every draft, as a test that starts a new tab needs. */
export function clearDrafts() {
	drafts.clear();
}
