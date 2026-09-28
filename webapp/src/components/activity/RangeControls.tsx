import { useSpinDelay } from "spin-delay";

import { FilterToggle } from "@/components/common/FilterToggle";

import { ACTIVITY_RANGE_OPTIONS, type ActivityRange } from "./activity-range";

export interface RangeControlsProps {
	range: ActivityRange;
	onRangeChange: (range: ActivityRange) => void;
	/**
	 * Whether what the page shows is still the previous range's while the one just chosen loads,
	 * which the regions show by draining their colours; this says it in words as well.
	 */
	updating: boolean;
}

/**
 * The range a page counts, and — beside it, where the reader just pressed — "Updating…" while the
 * previous range's figures stand in. The words appear only after a moment, so a quick read never
 * flashes them, and the live region they are spoken from is always there, so they are announced.
 */
export function RangeControls({ range, onRangeChange, updating }: RangeControlsProps) {
	const showUpdating = useSpinDelay(updating, { delay: 1000, minDuration: 500 });
	return (
		<div className="flex flex-wrap items-center gap-3">
			<span role="status" className="text-sm text-muted-foreground">
				{showUpdating ? "Updating…" : ""}
			</span>
			<FilterToggle
				label="Time range"
				options={ACTIVITY_RANGE_OPTIONS}
				value={range}
				onChange={onRangeChange}
			/>
		</div>
	);
}
