import { useSpinDelay } from "spin-delay";

import { FilterToggle, type FilterOption } from "./FilterToggle";

export interface RangeControlsProps<TRange extends string> {
	/** The ranges the page offers, in the order the toggle shows them. */
	options: readonly FilterOption<TRange>[];
	range: TRange;
	onRangeChange: (range: TRange) => void;
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
export function RangeControls<TRange extends string>({
	options,
	range,
	onRangeChange,
	updating,
}: RangeControlsProps<TRange>) {
	const showUpdating = useSpinDelay(updating, { delay: 1000, minDuration: 500 });
	return (
		<div className="flex flex-wrap items-center gap-3">
			<span role="status" className="text-sm text-muted-foreground">
				{showUpdating ? "Updating…" : ""}
			</span>
			<FilterToggle label="Time range" options={options} value={range} onChange={onRangeChange} />
		</div>
	);
}
