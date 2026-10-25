import { useState } from "react";

/**
 * What the trace shows on top of its steps, each part on its own, for the whole trace at once. The
 * reader sets each with one click in the trace's "Show" control.
 */
export interface TraceDisplay {
	/** Each step’s time and the gap since the step before. */
	times: boolean;
	/**
	 * The review's queue wait, run time and model, each piece of feedback's delivery details, and
	 * the facts under the record the reader started from.
	 */
	system: boolean;
	/**
	 * The other results and feedback of the record the reader started from, which otherwise
	 * fold into one line. The review's results table opens and folds on its own.
	 */
	siblings: boolean;
	connections: boolean;
}

export type TraceDisplayPart = keyof TraceDisplay;

export const TRACE_DISPLAY_PARTS: readonly {
	part: TraceDisplayPart;
	label: string;
	hint: string;
}[] = [
	{ part: "times", label: "Times", hint: "Each step’s time and the gap since the step before" },
	{
		part: "system",
		label: "System info",
		hint: "States, model, queue and run times, and the facts under where you started",
	},
	{
		part: "siblings",
		label: "Siblings",
		hint: "The other observations and feedback of the record you started from, not one line",
	},
	{
		part: "connections",
		label: "Connections",
		hint: "Lines from each observation to the feedback written from it, as the From column says",
	},
];

/** Times on, the rest off: the path of the record and when it happened, nothing more. */
export const DEFAULT_TRACE_DISPLAY: TraceDisplay = {
	times: true,
	system: false,
	siblings: false,
	connections: false,
};

/** Every part on, as the state matrix and a reader who wants the whole chain see it. */
export const FULL_TRACE_DISPLAY: TraceDisplay = {
	times: true,
	system: true,
	siblings: true,
	connections: true,
};

const STORAGE_KEY = "hephaestus.trace-display";

/** The stored choice, part by part; a part that is missing or not a boolean keeps its default. */
export function parseTraceDisplay(stored: string | null): TraceDisplay {
	if (stored === null) {
		return DEFAULT_TRACE_DISPLAY;
	}
	let value: unknown;
	try {
		value = JSON.parse(stored);
	} catch {
		return DEFAULT_TRACE_DISPLAY;
	}
	if (typeof value !== "object" || value === null) {
		return DEFAULT_TRACE_DISPLAY;
	}
	const part = (key: TraceDisplayPart) => {
		const chosen: unknown = Reflect.get(value, key);
		return typeof chosen === "boolean" ? chosen : DEFAULT_TRACE_DISPLAY[key];
	};
	return {
		times: part("times"),
		system: part("system"),
		siblings: part("siblings"),
		connections: part("connections"),
	};
}

/** Storage can be unavailable or full. The trace then starts from the default and forgets the choice. */
function readStoredDisplay(): TraceDisplay {
	try {
		return parseTraceDisplay(window.localStorage.getItem(STORAGE_KEY));
	} catch {
		return DEFAULT_TRACE_DISPLAY;
	}
}

function storeDisplay(display: TraceDisplay) {
	try {
		window.localStorage.setItem(STORAGE_KEY, JSON.stringify(display));
	} catch {
		// The choice lasts until the page closes.
	}
}

/**
 * What the reader chose to show in a trace, remembered in this browser for every trace they open.
 * It is a reading preference, not part of what a shared link points at, so the address leaves it out.
 */
export function useTraceDisplay(): [TraceDisplay, (display: TraceDisplay) => void] {
	const [display, setDisplay] = useState(readStoredDisplay);
	return [
		display,
		(next) => {
			setDisplay(next);
			storeDisplay(next);
		},
	];
}

/** Forgets the stored choice. A test starts from it, so no test inherits another's display. */
export function forgetTraceDisplay() {
	try {
		window.localStorage.removeItem(STORAGE_KEY);
	} catch {
		// Nothing was stored.
	}
}
