import { useSearch } from "@tanstack/react-router";
import { CrosshairIcon } from "lucide-react";

import { ROW_CONTROL_FOCUS } from "@/components/common/focus";
import { Toggle } from "@/components/ui/toggle";
import { useSearchState } from "@/lib/search-params";

import {
	isSameSelection,
	parseSelectionSearch,
	type ReviewSelection,
	readSelection,
	selectionSearch,
} from "./review-selection";

/** Track's icon, which the actions column's (i) also leads Track's line with. */
export const TrackIcon = CrosshairIcon;

export interface ReviewSelectToggleProps {
	/** What the row selects. */
	selection: ReviewSelection;
	/** The record, after "Track" in the accessible name, so every row's toggle has its own name. */
	name: string;
}

/**
 * A row's "Track": makes the row's record the one that every table narrows to, or clears it when it
 * already is. It writes the address, as the row's link does, and reads it back for its pressed
 * state, so the row needs no handler and a shared link shows the same row pressed. The row reads the
 * pressed toggle (`data-track`) to mark itself as the tracked record. Pressing it does not open the
 * row: the row's link does that.
 */
export function ReviewSelectToggle({ selection, name }: ReviewSelectToggleProps) {
	const current = useSearch({
		strict: false,
		select: (search) => readSelection(parseSelectionSearch(search)),
	});
	const setSearch = useSearchState();
	const pressed = isSameSelection(current, selection);
	return (
		<Toggle
			variant="outline"
			data-track
			className={ROW_CONTROL_FOCUS}
			aria-label={`Track ${name}`}
			title="Track"
			pressed={pressed}
			onPressedChange={(next) => {
				void setSearch(
					(previous) => ({ ...previous, ...selectionSearch(next ? selection : undefined) }),
					{ replace: true },
				);
			}}
		>
			<TrackIcon aria-hidden />
		</Toggle>
	);
}
