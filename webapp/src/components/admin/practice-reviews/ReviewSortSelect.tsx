import { ArrowDownWideNarrowIcon } from "lucide-react";
import { useId } from "react";

import { Field, FieldLabel } from "@/components/ui/field";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";

/**
 * One of an endpoint's orderings in the operator's words. The label says what arrives at the *top*,
 * because that is the only part of an ordering a reader of the first screenful can check.
 */
export interface ReviewSortItem<T extends string> {
	value: T;
	label: string;
}

export interface ReviewSortSelectProps<T extends string> {
	/** The orderings the list offers, the server's default first. */
	items: readonly [ReviewSortItem<T>, ...ReviewSortItem<T>[]];
	value: T | undefined;
	onChange: (sort: T | undefined) => void;
}

/**
 * A list has no sortable column headers, so the ordering has to be offered explicitly.
 *
 * The first item is reported as `undefined` rather than as its value: it is the server's default,
 * so writing it into the URL would put a parameter in every link that changes nothing.
 */
export function ReviewSortSelect<T extends string>({
	items,
	value,
	onChange,
}: ReviewSortSelectProps<T>) {
	const sortId = useId();
	const sortLabelId = useId();
	const [byDefault] = items;
	return (
		<Field orientation="horizontal" className="w-auto max-w-full flex-wrap text-sm">
			<FieldLabel id={sortLabelId} htmlFor={sortId} className="text-muted-foreground">
				Sort
			</FieldLabel>
			<Select
				items={items}
				value={value ?? byDefault.value}
				onValueChange={(next: string | null) =>
					onChange(items.find((item) => item.value === next && item !== byDefault)?.value)
				}
			>
				<SelectTrigger id={sortId} size="sm" className="w-52 max-w-full">
					<ArrowDownWideNarrowIcon aria-hidden className="text-muted-foreground" />
					<SelectValue />
				</SelectTrigger>
				<SelectContent aria-labelledby={sortLabelId}>
					{items.map((item) => (
						<SelectItem key={item.value} value={item.value}>
							{item.label}
						</SelectItem>
					))}
				</SelectContent>
			</Select>
		</Field>
	);
}
