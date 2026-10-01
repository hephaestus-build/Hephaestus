import { useEffect, useRef } from "react";

import { occurrenceDomId } from "./trace-format";

/**
 * "Rests on" from the practice table to the timeline, which sits on another tab: the jump opens that
 * tab first, then moves focus to the occurrence once the timeline exists.
 */
export function useOccurrenceJump(timelineShown: boolean, showTimeline: () => void) {
	const pending = useRef<string>(undefined);
	useEffect(() => {
		const id = pending.current;
		if (id === undefined || !timelineShown) {
			return;
		}
		pending.current = undefined;
		document.getElementById(occurrenceDomId(id))?.focus();
	}, [timelineShown]);
	return (signalId: string) => {
		pending.current = signalId;
		showTimeline();
	};
}
