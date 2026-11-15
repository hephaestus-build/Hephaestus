import { useSyncExternalStore } from "react";
import { z } from "zod";

import { tourRecordKey } from "./tour-start";

const progressSchema = z.object({
	/** The step to continue from: step ids, so a later change to a chapter cannot point at a wrong step. */
	place: z.object({ chapter: z.string(), step: z.string() }).nullable(),
	done: z.array(z.string()),
});

export type TourProgress = z.infer<typeof progressSchema>;

const FRESH: TourProgress = { place: null, done: [] };

/** A value that does not parse is a fresh start: the worst it can cost is a checkmark. */
export function readTourProgress(raw: string | null): TourProgress {
	if (raw === null) {
		return FRESH;
	}
	try {
		const parsed = progressSchema.safeParse(JSON.parse(raw));
		return parsed.success ? parsed.data : FRESH;
	} catch {
		return FRESH;
	}
}

/** Records that storage refused, or that belong to no account, kept until the page closes. */
const unsaved = new Map<string, string>();
const listeners = new Set<() => void>();
const NO_ACCOUNT = "no-account";

function subscribe(listener: () => void): () => void {
	listeners.add(listener);
	// Another tab of this browser can finish a chapter too.
	window.addEventListener("storage", listener);
	return () => {
		listeners.delete(listener);
		window.removeEventListener("storage", listener);
	};
}

function readRecord(key: string | undefined): string | null {
	if (key === undefined) {
		return unsaved.get(NO_ACCOUNT) ?? null;
	}
	// A record that storage refused to save is newer than what storage still holds.
	const refused = unsaved.get(key);
	if (refused !== undefined) {
		return refused;
	}
	try {
		return window.localStorage.getItem(key);
	} catch {
		return null;
	}
}

function writeRecord(key: string | undefined, record: string) {
	if (key === undefined) {
		unsaved.set(NO_ACCOUNT, record);
	} else {
		try {
			window.localStorage.setItem(key, record);
			unsaved.delete(key);
		} catch {
			unsaved.set(key, record);
		}
	}
	for (const listener of listeners) {
		listener();
	}
}

/**
 * Where the reader left the tour and which chapters they finished, in this browser and for this
 * account only; without an account, until the page closes. Nothing starts or returns because of
 * it, so a new device costs a checkmark and a place to continue from. Keeping it on the server would
 * add a column, an export entry and an erasure entry for that. One record serves every reader of
 * it, so a page beside the tour shows a chapter finished as soon as the tour finishes it.
 */
export function useTourProgress(accountId: string | undefined) {
	const key = accountId === undefined ? undefined : tourRecordKey("progress", accountId);
	const record = useSyncExternalStore(
		subscribe,
		() => readRecord(key),
		() => null,
	);
	const progress = readTourProgress(record);
	const update = (next: TourProgress) => writeRecord(key, JSON.stringify(next));
	return {
		progress,
		stopAt: (chapter: string, step: string) => update({ ...progress, place: { chapter, step } }),
		finish: (chapter: string) =>
			update({
				place: null,
				done: progress.done.includes(chapter) ? progress.done : [...progress.done, chapter],
			}),
		reset: () => update(FRESH),
	};
}
