import { format } from "date-fns";
import { CalendarIcon } from "lucide-react";
import type { DateRange } from "react-day-picker";

import { useNow } from "@/components/common/use-now";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { hasText } from "@/lib/text";

export interface DateRangeFacetProps {
	/** Name the filtered date explicitly; Recorded and Composed select different facts. */
	title: string;
	value: DateRange | undefined;
	onChange: (range: DateRange | undefined) => void;
}

/** The applied range as the facet shows it, for a pill that names the same range. */
export function dateRangeLabel(range: DateRange): string | undefined {
	if (!range.from) {
		return undefined;
	}
	if (!range.to) {
		return `From ${format(range.from, "d MMM yyyy")}`;
	}
	// Day before month, as every other date in the app.
	return `${format(range.from, "d MMM")} – ${format(range.to, "d MMM yyyy")}`;
}

export function DateRangeFacet({ title, value, onChange }: DateRangeFacetProps) {
	const today = new Date(useNow());
	const applied = value?.from ? dateRangeLabel(value) : undefined;
	return (
		<Popover>
			<PopoverTrigger
				render={
					<Button
						variant="outline"
						size="sm"
						className="h-8 border-dashed font-normal"
						aria-label={hasText(applied) ? `${title}: ${applied}` : title}
					>
						<CalendarIcon aria-hidden />
						{title}
						{hasText(applied) && (
							<>
								{" "}
								<Separator
									orientation="vertical"
									className="mx-0.5 data-[orientation=vertical]:h-4"
								/>
								<Badge variant="secondary" size="xs">
									{applied}
								</Badge>
							</>
						)}
					</Button>
				}
			/>
			<PopoverContent className="w-auto p-0" align="start">
				<PopoverTitle className="sr-only">Choose a {title.toLowerCase()} date range</PopoverTitle>
				<Calendar
					// oxlint-disable-next-line jsx-a11y/no-autofocus -- The popover holds nothing but this calendar, so opening it puts the arrow keys on the day grid.
					autoFocus
					mode="range"
					defaultMonth={value?.from}
					selected={value}
					onSelect={onChange}
					numberOfMonths={1}
					// Every date this filters is something that already happened: a future day can only
					// select nothing.
					disabled={{ after: today }}
					endMonth={today}
				/>
				{/* The way out every other facet in the toolbar has: without it a picked range can only be
				    replaced by picking another, never removed. */}
				{hasText(applied) && (
					<>
						<Separator />
						<Button
							variant="ghost"
							size="sm"
							className="h-8 w-full rounded-t-none font-normal"
							onClick={() => onChange(undefined)}
						>
							Clear selection
						</Button>
					</>
				)}
			</PopoverContent>
		</Popover>
	);
}
