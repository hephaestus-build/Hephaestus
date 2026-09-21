import { useSyncExternalStore } from "react";

/** The finest phrase rendered from this clock is a minute, so this keeps every label within half a step. */
const TICK_MS = 30_000;

// oxlint-disable-next-line no-restricted-properties -- The component tree's single wall-clock read; `useNow` below publishes it so nothing else has to take one.
const readClock = (): number => Date.now();

const listeners = new Set<() => void>();
let intervalId: ReturnType<typeof setInterval> | undefined;
let now = readClock();

function subscribe(onStoreChange: () => void): () => void {
	listeners.add(onStoreChange);
	if (intervalId === undefined) {
		// The clock may have been parked for hours since the last unsubscribe.
		now = readClock();
		intervalId = setInterval(() => {
			now = readClock();
			for (const listener of listeners) {
				listener();
			}
		}, TICK_MS);
	}
	return () => {
		listeners.delete(onStoreChange);
		if (listeners.size === 0 && intervalId !== undefined) {
			clearInterval(intervalId);
			intervalId = undefined;
		}
	};
}

/**
 * Must stay the millisecond `now` rather than a tick counter. A counter is not an input to the
 * phrases callers derive, so React Compiler would memoise such a phrase and freeze it on screen
 * while the counter ticked underneath it.
 */
function getSnapshot(): number {
	return now;
}

/**
 * The current instant in milliseconds, from one clock shared by every subscriber on the page and
 * re-published on a fixed tick, so a reading derived from it ages on its own.
 *
 * This is how a component asks for the time. A clock read in a render body instead makes the render
 * non-deterministic — two components mounted in the same commit disagree, a story or test snapshot
 * never repeats, and the reading freezes the moment anything memoises it.
 */
export function useNow(): number {
	return useSyncExternalStore(subscribe, getSnapshot);
}
