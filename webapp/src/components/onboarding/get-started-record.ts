import { createContext, useSyncExternalStore } from "react";

/**
 * Where the reader's Get started list stands in the corner, in this browser, for one account and
 * workspace. Absent until the list first shows. The record decides only whether the offer shows,
 * never what is true: each row's status comes from the server or from the tour's own record.
 *
 * - `open`: the list has shown, so its first-visit open is spent and the pill stays.
 * - `hidden`: the reader hid the list. The account menu shows it again.
 * - `finished`: every step was done and the reader closed the list. The account menu shows it again.
 */
export type GetStartedRecord = "open" | "hidden" | "finished";

const RECORDS: readonly GetStartedRecord[] = ["open", "hidden", "finished"];

/**
 * One record per account and workspace, so a second person on a shared browser starts from their
 * own. A lost record costs one more first-visit open, or a pill on a new device, which interrupts
 * nothing. A server record would need a column, an export entry and an erasure entry for that.
 */
export const getStartedRecordKey = (accountId: string, workspaceSlug: string) =>
	`hephaestus:get-started:${accountId}:${workspaceSlug}`;

/** Records that storage refused, kept until the page closes. */
const unsaved = new Map<string, GetStartedRecord>();
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
	listeners.add(listener);
	// Another tab of this browser can hide or finish the list too.
	window.addEventListener("storage", listener);
	return () => {
		listeners.delete(listener);
		window.removeEventListener("storage", listener);
	};
}

function readRecord(key: string): GetStartedRecord | undefined {
	const refused = unsaved.get(key);
	if (refused !== undefined) {
		return refused;
	}
	try {
		const stored = window.localStorage.getItem(key);
		return RECORDS.find((record) => record === stored);
	} catch {
		return undefined;
	}
}

/** Keeps the record, and tells every reader of it. */
export function writeGetStartedRecord(
	accountId: string,
	workspaceSlug: string,
	record: GetStartedRecord,
) {
	const key = getStartedRecordKey(accountId, workspaceSlug);
	try {
		window.localStorage.setItem(key, record);
		unsaved.delete(key);
	} catch {
		unsaved.set(key, record);
	}
	for (const listener of listeners) {
		listener();
	}
}

/** The reader's record for this workspace; nothing without an account. */
export function useGetStartedRecord(
	accountId: string | undefined,
	workspaceSlug: string | undefined,
): GetStartedRecord | undefined {
	const key =
		accountId === undefined || workspaceSlug === undefined
			? undefined
			: getStartedRecordKey(accountId, workspaceSlug);
	return useSyncExternalStore(
		subscribe,
		() => (key === undefined ? undefined : readRecord(key)),
		() => undefined,
	);
}

/** How the card opened: the reader asked, so focus moves into it, or it opened by itself, so focus stays. */
export type GetStartedOpening = "reader" | "self";

/**
 * What a page can ask of the Get started corner. Absent where the corner cannot show, such as in a
 * user view, on an admin page, or for someone who is not a member.
 */
export interface GetStartedCorner {
	/** Shows the list again after Hide or after it finished, and opens it with focus in it. */
	show: () => void;
	/** Opens the card again without moving focus, as after a tour chapter that a row started. */
	reopen: () => void;
}

export const GetStartedCornerContext = createContext<GetStartedCorner | undefined>(undefined);
