import { useState } from "react";
import { z } from "zod";

const STORAGE_KEY = "hephaestus:guided-tour:practice-profile";

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

function loadTourProgress(): TourProgress {
	try {
		return readTourProgress(window.localStorage.getItem(STORAGE_KEY));
	} catch {
		return FRESH;
	}
}

/**
 * Where the reader left the tour and which chapters they finished, in this browser. Nothing starts or
 * returns because of it, so a new device costs a checkmark and a place to continue from. Keeping it on
 * the server would add a column, an export entry and an erasure entry for that.
 */
export function useTourProgress() {
	const [progress, setProgress] = useState(loadTourProgress);
	const update = (next: TourProgress) => {
		setProgress(next);
		try {
			window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
		} catch {
			// The progress lasts until the page closes.
		}
	};
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
