import { useSyncExternalStore } from "react";

/**
 * Local demo: what the tour's launcher bottom right and the rest of the app share. The parts the
 * reader finished in this browser, a part another page asked the profile to start at, and Heph's
 * chat, so that one Heph button bottom right serves both.
 */
const PARTS_KEY = "practice-profile:tour-parts";
const START_KEY = "practice-profile:tour-start";

export interface TourProgress {
	/** The parts (by id) whose last step the reader reached. */
	done: string[];
	/** The reader reached the tour's last word. */
	finished: boolean;
}

const EMPTY: TourProgress = { done: [], finished: false };

let progress: TourProgress | undefined;
let openChat: (() => void) | undefined;
let launchers = 0;
const listeners = new Set<() => void>();

function notify() {
	for (const listener of listeners) {
		listener();
	}
}

function subscribe(listener: () => void): () => void {
	listeners.add(listener);
	return () => {
		listeners.delete(listener);
	};
}

function read(): TourProgress {
	if (progress !== undefined) {
		return progress;
	}
	progress = EMPTY;
	try {
		const raw = window.localStorage.getItem(PARTS_KEY);
		const parsed: unknown = raw === null ? null : JSON.parse(raw);
		if (typeof parsed === "object" && parsed !== null && "done" in parsed) {
			const done: unknown = parsed.done;
			progress = {
				done: Array.isArray(done)
					? done.filter((name): name is string => typeof name === "string")
					: [],
				finished: "finished" in parsed && parsed.finished === true,
			};
		}
	} catch {
		// Blocked storage: the tour starts without ticks.
	}
	return progress;
}

function write(next: TourProgress) {
	progress = next;
	try {
		window.localStorage.setItem(PARTS_KEY, JSON.stringify(next));
	} catch {
		// Blocked storage: the ticks last until the page reloads.
	}
	notify();
}

export function useTourProgress(): TourProgress {
	return useSyncExternalStore(subscribe, read, () => EMPTY);
}

export function markPartDone(part: string) {
	const current = read();
	if (!current.done.includes(part)) {
		write({ ...current, done: [...current.done, part] });
	}
}

export function markTourFinished() {
	const current = read();
	if (!current.finished) {
		write({ ...current, finished: true });
	}
}

/** Another page asks the profile to open its tour at a part, for the next visit only. */
export function requestTourStart(part: string) {
	try {
		window.sessionStorage.setItem(START_KEY, part);
	} catch {
		// Blocked storage: the profile opens without the tour.
	}
}

/** The part another page asked for, left in place until `clearTourStart`. */
export function peekTourStart(): string | null {
	try {
		return window.sessionStorage.getItem(START_KEY);
	} catch {
		return null;
	}
}

export function clearTourStart() {
	try {
		window.sessionStorage.removeItem(START_KEY);
	} catch {
		// Blocked storage: nothing was kept.
	}
}

/** Heph's chat offers itself here, so the tour's launcher can open it. */
export function offerHephChat(open: (() => void) | undefined) {
	openChat = open;
	notify();
}

export function useHephChat(): (() => void) | undefined {
	return useSyncExternalStore(
		subscribe,
		() => openChat,
		() => undefined,
	);
}

/** The tour's launcher counts itself in while it shows, so Heph's chat leaves the corner to it. */
export function registerTourLauncher(): () => void {
	launchers += 1;
	notify();
	return () => {
		launchers -= 1;
		notify();
	};
}

export function useTourLauncherShown(): boolean {
	return useSyncExternalStore(
		subscribe,
		() => launchers > 0,
		() => false,
	);
}
