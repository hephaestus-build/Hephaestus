import { cn } from "cn";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

export interface FilterOption<TValue extends string> {
	value: TValue;
	label: string;
	/** Shorter text for the toggle row, where horizontal space is the constraint. */
	shortLabel?: string;
	/** Appended to the accessible name so a shortened chip still names the filter in full. */
	srSuffix?: string;
}

export interface FilterToggleProps<TValue extends string> {
	label: string;
	options: readonly FilterOption<TValue>[];
	value: TValue;
	onChange: (value: TValue) => void;
	className?: string;
}

/**
 * One filter, as a select on a narrow viewport and a toggle row on a wide one.
 *
 * `role="toolbar"` rather than Base UI's default `group`: the group ships a roving tabindex, and
 * `toolbar` is the role that contract belongs to. `radiogroup` would be worse — the items are
 * `aria-pressed`, not radios.
 */
export function FilterToggle<TValue extends string>({
	label,
	options,
	value,
	onChange,
	className,
}: FilterToggleProps<TValue>) {
	return (
		<div className={cn("min-w-0", className)}>
			{/* `items` is what lets `SelectValue` render the chosen label without a render prop. */}
			<Select
				items={options.map(({ value: optionValue, label: optionLabel }) => ({
					value: optionValue,
					label: optionLabel,
				}))}
				value={value}
				onValueChange={(next) => next && onChange(next)}
			>
				<SelectTrigger className="w-full sm:hidden" aria-label={label}>
					<SelectValue />
				</SelectTrigger>
				<SelectContent aria-label={label}>
					{options.map((option) => (
						<SelectItem key={option.value} value={option.value}>
							{option.label}
						</SelectItem>
					))}
				</SelectContent>
			</Select>
			<ToggleGroup
				role="toolbar"
				value={[value]}
				onValueChange={(next) => {
					const chosen = next[0];
					if (chosen) onChange(chosen);
				}}
				variant="outline"
				size="sm"
				aria-label={label}
				className="hidden sm:flex"
			>
				{options.map((option) => (
					<ToggleGroupItem key={option.value} value={option.value} className="min-w-0">
						{option.shortLabel ?? option.label}
						{option.srSuffix && <span className="sr-only"> {option.srSuffix}</span>}
					</ToggleGroupItem>
				))}
			</ToggleGroup>
		</div>
	);
}
