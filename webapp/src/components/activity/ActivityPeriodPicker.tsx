import { CalendarIcon } from "lucide-react";
import { useState } from "react";
import type { DateRange } from "react-day-picker";

import { RangeControls } from "@/components/common/RangeControls";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";

import { ACTIVITY_PRESET_OPTIONS, type ActivityPeriod, periodLabel } from "./activity-period";

export interface ActivityPeriodPickerProps {
	period: ActivityPeriod;
	onPeriodChange: (period: ActivityPeriod) => void;
	/** Whether the figures on the page are still the previous period's. */
	updating: boolean;
}

/**
 * The period a page counts: one press for a preset, or a custom range of days from a calendar.
 * The calendar applies a range only when asked, so the page does not count a half-picked range.
 */
export function ActivityPeriodPicker({
	period,
	onPeriodChange,
	updating,
}: ActivityPeriodPickerProps) {
	const [open, setOpen] = useState(false);
	const custom = period.kind === "custom" ? period : undefined;
	return (
		<RangeControls
			options={ACTIVITY_PRESET_OPTIONS}
			range={period.kind === "preset" ? period.preset : undefined}
			onRangeChange={(preset) => onPeriodChange({ kind: "preset", preset })}
			updating={updating}
		>
			<Popover open={open} onOpenChange={setOpen}>
				<PopoverTrigger
					render={
						<Button
							variant={custom ? "secondary" : "outline"}
							size="sm"
							aria-label={custom ? `${periodLabel(custom)}, custom range` : undefined}
						>
							<CalendarIcon aria-hidden />
							{custom ? periodLabel(custom) : "Custom range"}
						</Button>
					}
				/>
				<PopoverContent className="w-auto p-0" align="end">
					<PopoverTitle className="sr-only">Choose the days to count</PopoverTitle>
					<CustomRange
						initial={custom}
						onApply={(range) => {
							onPeriodChange({ kind: "custom", ...range });
							setOpen(false);
						}}
					/>
				</PopoverContent>
			</Popover>
		</RangeControls>
	);
}

/** The calendar's draft, seeded each time the popover opens, since its content mounts on open. */
function CustomRange({
	initial,
	onApply,
}: {
	initial: { from: Date; to: Date } | undefined;
	onApply: (range: { from: Date; to: Date }) => void;
}) {
	const today = new Date(useNow());
	const [draft, setDraft] = useState<DateRange | undefined>(initial);
	const from = draft?.from;
	return (
		<>
			<Calendar
				// oxlint-disable-next-line jsx-a11y/no-autofocus -- The popover holds this calendar and its button, so opening it puts the arrow keys on the day grid.
				autoFocus
				mode="range"
				defaultMonth={initial?.from ?? today}
				selected={draft}
				onSelect={setDraft}
				disabled={{ after: today }}
				excludeDisabled
				numberOfMonths={1}
			/>
			<Separator />
			<div className="flex items-center justify-between gap-3 p-2">
				<p className="text-xs text-muted-foreground" aria-live="polite">
					{from
						? periodLabel({ kind: "custom", from, to: draft.to ?? from })
						: "Pick the first day"}
				</p>
				<Button
					size="sm"
					disabled={from === undefined}
					onClick={() => {
						if (from) {
							onApply({ from, to: draft.to ?? from });
						}
					}}
				>
					Apply
				</Button>
			</div>
		</>
	);
}
