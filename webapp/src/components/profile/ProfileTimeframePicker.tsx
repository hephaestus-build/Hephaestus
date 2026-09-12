import { format, isSameYear, subDays } from "date-fns";
import { CalendarDays, CalendarIcon, CalendarRange, Clock } from "lucide-react";
import { useState } from "react";
import type { DateRange } from "react-day-picker";

import { cn } from "cn";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
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
	type LeaderboardSchedule,
	type TimeframePreset,
} from "@/lib/timeframe";

export interface ProfileTimeframePickerProps {
	afterDate?: string;
	beforeDate?: string;
	onTimeframeChange?: (afterDate: string, beforeDate?: string) => void;
	enableAllActivity?: boolean;
	schedule?: LeaderboardSchedule;
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

export function ProfileTimeframePicker({
	afterDate,
	beforeDate,
	onTimeframeChange,
	enableAllActivity = true,
	schedule = DEFAULT_SCHEDULE,
}: ProfileTimeframePickerProps) {
	const [customRangeOpen, setCustomRangeOpen] = useState(false);
	const now = new Date(useNow());
	const selectedPreset = detectPresetFromDates(
		now,
		afterDate,
		beforeDate,
		schedule,
		enableAllActivity,
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
	const items = enableAllActivity
		? [{ value: "all-activity", label: formatDropdownLabel("all-activity") }, ...baseItems]
		: baseItems;

	const handlePresetChange = (preset: TimeframePreset) => {
		if (preset === "custom") {
			setCustomRangeOpen(true);
			return;
		}
		setCustomRangeOpen(false);
		const range = formatDateRangeForApi(getDateRangeForPreset(now, preset, schedule));
		onTimeframeChange?.(range.after, range.before);
	};

	const handleCustomRangeChange = (range: DateRange | undefined) => {
		if (!range?.from) return;
		const dates = formatDateRangeForApi(
			getDateRangeForPreset(now, "custom", schedule, { from: range.from, to: range.to }),
		);
		onTimeframeChange?.(dates.after, dates.before);
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
				return `${format(from, "MMM d")} – ${format(to, "d")}`;
			}
			return `${format(from, "MMM d")} – ${format(to, "MMM d")}`;
		}
		return `${format(from, "MMM d, yyyy")} – ${format(to, "MMM d, yyyy")}`;
	};

	return (
		<div className="flex flex-wrap items-center gap-2">
			<Select
				value={selectedPreset}
				onValueChange={(value) => value && handlePresetChange(value)}
				items={items}
			>
				<SelectTrigger className="w-65" aria-label="Timeframe">
					<SelectValue placeholder="Select timeframe" />
				</SelectTrigger>
				<SelectContent aria-label="Timeframe">
					{enableAllActivity && (
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
				<Popover open={customRangeOpen} onOpenChange={setCustomRangeOpen}>
					<PopoverTrigger
						render={
							<Button
								variant="outline"
								aria-label="Choose custom dates"
								className={cn(
									"justify-start text-left font-normal",
									!customRange?.from && "text-muted-foreground",
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
			)}
		</div>
	);
}
