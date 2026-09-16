import { format } from "date-fns";
import { CalendarIcon } from "lucide-react";
import type { DateRange } from "react-day-picker";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";

export interface DateRangeFacetProps {
	/**
	 * Which date this filters, in the words the rows use: "Observed", "Composed", "Changed".
	 *
	 * Required, and deliberately not defaulted to "Date": several screens filter different timestamps
	 * through this control, and "Date" names the category rather than the value. A filter's name has to
	 * be "concrete and predictable" (NN/g, "Filter Categories and Values"), and a default would let the
	 * next screen ship unlabelled.
	 */
	title: string;
	value: DateRange | undefined;
	onChange: (range: DateRange | undefined) => void;
}

function rangeLabel(range: DateRange): string | undefined {
	if (!range.from) return undefined;
	if (!range.to) return `From ${format(range.from, "MMM d, yyyy")}`;
	return `${format(range.from, "MMM d")} – ${format(range.to, "MMM d, yyyy")}`;
}

export function DateRangeFacet({ title, value, onChange }: DateRangeFacetProps) {
	const applied = value?.from ? rangeLabel(value) : undefined;
	return (
		<Popover>
			<PopoverTrigger
				render={
					<Button
						variant="outline"
						size="sm"
						className="h-8 border-dashed font-normal"
						aria-label={applied ? `${title}: ${applied}` : title}
					>
						<CalendarIcon aria-hidden />
						{title}
						{applied && (
							<>
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
				/>
				{/* The way out every other facet in the toolbar has: without it a picked range can only be
				    replaced by picking another, never removed. */}
				{applied && (
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
