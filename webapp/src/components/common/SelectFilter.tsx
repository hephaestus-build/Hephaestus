import { useId } from "react";

import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";

/**
 * Base UI treats "" as "no selection", so the "everything" choice needs a value of its own. It never
 * leaves this file: in a URL it would filter for a value nothing has.
 */
const ALL_OPTION = "__all";

export interface SelectFilterProps<TValue extends string> {
	label: string;
	/** The choice that filters nothing: "All work". */
	allLabel: string;
	options: readonly { value: TValue; label: string }[];
	/** The value filtered for, or `undefined` for everything. */
	value: TValue | undefined;
	onChange: (value: TValue | undefined) => void;
}

/** One labelled select in a `FilterToolbar`, whose first choice is no filter at all. */
export function SelectFilter<TValue extends string>({
	label,
	allLabel,
	options,
	value,
	onChange,
}: SelectFilterProps<TValue>) {
	const id = useId();
	const labelId = `${id}-label`;
	const items = [{ value: ALL_OPTION, label: allLabel }, ...options];
	return (
		<div className="flex min-w-0 items-center gap-2">
			<Label id={labelId} htmlFor={id} className="shrink-0 text-muted-foreground">
				{label}
			</Label>
			<Select
				items={items}
				value={value ?? ALL_OPTION}
				onValueChange={(next) => onChange(options.find((option) => option.value === next)?.value)}
			>
				<SelectTrigger id={id} className="w-56 max-w-full">
					<SelectValue placeholder={allLabel} />
				</SelectTrigger>
				<SelectContent aria-labelledby={labelId}>
					{items.map((item) => (
						<SelectItem key={item.value} value={item.value}>
							{item.label}
						</SelectItem>
					))}
				</SelectContent>
			</Select>
		</div>
	);
}
