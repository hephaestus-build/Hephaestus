import { addDays, addWeeks, endOfMonth, format, isSameYear, startOfMonth, subDays } from "date-fns";
import { CalendarDays, CalendarIcon, CalendarRange, Clock } from "lucide-react";
import { useState } from "react";
import type { DateRange } from "react-day-picker";

import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { asDate } from "@/lib/dates";
import {
	DEFAULT_SCHEDULE,
	detectPresetFromDates,
	formatDateRangeForApi,
	formatDropdownLabel,
	getDateRangeForPreset,
	type TimeframePreset,
	type LeaderboardSchedule,
} from "@/lib/timeframe";
import { cn } from "@/lib/utils";

export interface TimeframeFilterProps {
	onTimeframeChange?: (afterDate: string, beforeDate?: string, timeframe?: string) => void;
	/** Selected ISO interval: inclusive start and exclusive end. */
	afterDate?: string;
	beforeDate?: string;
	leaderboardSchedule?: LeaderboardSchedule;
	/**
	 * When true, presets "this week/this month" emit only afterDate (open-ended).
	 * "Last week/last month" always send both bounds so their labels remain bounded.
	 */
	openEndedPresets?: boolean;
	/**
	 * Enable an "All activity" option that covers the full history.
	 */
	enableAllActivityOption?: boolean;
}

function PresetIcon({ preset, className }: { preset: TimeframePreset; className?: string }) {
	const iconClass = cn("h-4 w-4 shrink-0", className);

	switch (preset) {
		case "all-activity":
			return <Clock className={iconClass} />;
		case "this-week":
		case "last-week":
			return <CalendarDays className={iconClass} />;
		case "this-month":
		case "last-month":
			return <CalendarIcon className={iconClass} />;
		case "custom":
			return <CalendarRange className={iconClass} />;
	}
}

export function TimeframeFilter({
	onTimeframeChange,
	leaderboardSchedule,
	afterDate,
	beforeDate,
	openEndedPresets = false,
	enableAllActivityOption = false,
}: TimeframeFilterProps) {
	const schedule = leaderboardSchedule ?? DEFAULT_SCHEDULE;
	const [customRangeOpen, setCustomRangeOpen] = useState(false);
	const now = new Date(useNow());
	const selectedPreset = detectPresetFromDates(
		now,
		afterDate,
		beforeDate,
		schedule,
		enableAllActivityOption,
	);
	const startDate = asDate(afterDate);
	const before = asDate(beforeDate);
	const customRange = startDate
		? { from: startDate, to: before ? subDays(before, 1) : undefined }
		: undefined;

	const baseItems: { value: TimeframePreset; label: string }[] = [
		{ value: "this-week", label: formatDropdownLabel("this-week") },
		{ value: "last-week", label: formatDropdownLabel("last-week") },
		{ value: "this-month", label: formatDropdownLabel("this-month") },
		{ value: "last-month", label: formatDropdownLabel("last-month") },
		{ value: "custom", label: formatDropdownLabel("custom") },
	];
	const items = enableAllActivityOption
		? [{ value: "all-activity", label: formatDropdownLabel("all-activity") }, ...baseItems]
		: baseItems;

	const handlePresetChange = (preset: TimeframePreset) => {
		if (preset === "custom") {
			setCustomRangeOpen(true);
			return;
		}
		setCustomRangeOpen(false);
		const range = getDateRangeForPreset(now, preset, schedule);
		if (!openEndedPresets && !range.before) {
			if (preset === "this-week") range.before = addWeeks(range.after, 1);
			if (preset === "this-month") range.before = startOfMonth(addDays(endOfMonth(range.after), 1));
		}
		const dates = formatDateRangeForApi(range);
		onTimeframeChange?.(dates.after, dates.before, preset);
	};

	const handleCustomRangeChange = (range: DateRange | undefined) => {
		if (!range?.from) return;
		const dates = formatDateRangeForApi(
			getDateRangeForPreset(now, "custom", schedule, { from: range.from, to: range.to }),
		);
		onTimeframeChange?.(dates.after, dates.before, "custom");
	};

	const formatCustomRangeLabel = () => {
		if (!customRange?.from) return "Pick dates";
		const from = customRange.from;
		const to = customRange.to;

		if (!to) {
			return `since ${format(from, "MMM d")}`;
		}

		if (isSameYear(from, to)) {
			if (from.getMonth() === to.getMonth()) {
				return `${format(from, "MMM d")} - ${format(to, "d")}`;
			}
			return `${format(from, "MMM d")} - ${format(to, "MMM d")}`;
		}
		return `${format(from, "MMM d, yyyy")} - ${format(to, "MMM d, yyyy")}`;
	};

	return (
		<div className="space-y-1.5">
			<Label id="timeframe-label" htmlFor="timeframe">
				Timeframe
			</Label>
			<Select
				value={selectedPreset}
				onValueChange={(value) => value && handlePresetChange(value)}
				items={items}
			>
				<SelectTrigger id="timeframe" className="w-full">
					<SelectValue placeholder="Select timeframe" />
				</SelectTrigger>
				<SelectContent aria-labelledby="timeframe-label">
					{enableAllActivityOption && (
						<SelectItem value="all-activity">
							<PresetIcon preset="all-activity" />
							<span>{formatDropdownLabel("all-activity")}</span>
						</SelectItem>
					)}
					<SelectItem value="this-week">
						<PresetIcon preset="this-week" />
						<span>{formatDropdownLabel("this-week")}</span>
					</SelectItem>
					<SelectItem value="last-week">
						<PresetIcon preset="last-week" />
						<span>{formatDropdownLabel("last-week")}</span>
					</SelectItem>
					<SelectItem value="this-month">
						<PresetIcon preset="this-month" />
						<span>{formatDropdownLabel("this-month")}</span>
					</SelectItem>
					<SelectItem value="last-month">
						<PresetIcon preset="last-month" />
						<span>{formatDropdownLabel("last-month")}</span>
					</SelectItem>
					<SelectItem value="custom">
						<PresetIcon preset="custom" />
						<span>{formatDropdownLabel("custom")}</span>
					</SelectItem>
				</SelectContent>
			</Select>
			{(selectedPreset === "custom" || customRangeOpen) && (
				<div className="pt-2">
					<div className="grid gap-2">
						<Popover open={customRangeOpen} onOpenChange={setCustomRangeOpen}>
							<PopoverTrigger
								render={
									<Button
										id="date"
										aria-label="Choose custom dates"
										variant="outline"
										className={cn(
											"w-full justify-start text-left font-normal",
											!customRange && "text-muted-foreground",
										)}
									>
										<CalendarIcon className="mr-2 h-4 w-4" />
										{formatCustomRangeLabel()}
									</Button>
								}
							/>
							<PopoverContent className="w-auto p-0" align="start">
								<Calendar
									// oxlint-disable-next-line jsx-a11y/no-autofocus -- Choosing "custom" opens this popover for the sole purpose of picking a range, so the day grid takes focus with it.
									autoFocus
									mode="range"
									defaultMonth={customRange?.from}
									selected={customRange}
									onSelect={handleCustomRangeChange}
									numberOfMonths={2}
								/>
							</PopoverContent>
						</Popover>
					</div>
				</div>
			)}
		</div>
	);
}
