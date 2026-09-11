import { ChevronsUpDownIcon, type LucideIcon, PlusCircleIcon } from "lucide-react";

import { cn } from "cn";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Combobox,
	ComboboxContent,
	ComboboxEmpty,
	ComboboxIcon,
	ComboboxItem,
	ComboboxItemIndicator,
	ComboboxList,
	ComboboxSearchInput,
	ComboboxSeparator,
	ComboboxTrigger,
	useComboboxFilter,
} from "@/components/ui/combobox";
import { Separator } from "@/components/ui/separator";

export interface FacetOption<TValue extends string | number = string> {
	value: TValue;
	label: string;
	description?: string;
	/**
	 * The same icon the value's badge carries elsewhere, so a filter reads as the thing it filters.
	 * Options built from a status registry get this for free; see `statusFacetOptions`.
	 */
	icon?: LucideIcon;
	/** Tone for `icon`, matching its badge variant. */
	iconClassName?: string;
}

/**
 * A facet whose options are fetched — the workspace's own catalogue rather than a fixed registry —
 * so it can be empty for two different reasons and has to say which.
 *
 * Whoever fetches owes all three fields; the control never learns where they came from. The flags
 * are required rather than optional on purpose: "absent" and `false` would otherwise be two
 * spellings of "loaded fine", and a caller that forgot one would render an empty facet as a
 * finished one.
 *
 * Generic over the option, because a facet that offers people offers a person
 * (`ReviewPeople extends FacetSource<PersonOption>`), not a `{value, label}`.
 */
export interface FacetSource<TOption = FacetOption> {
	options: TOption[];
	isLoading: boolean;
	isError: boolean;
}

export interface FacetMultiSelectProps<TValue extends string | number> {
	title: string;
	options: FacetOption<TValue>[];
	selected: readonly TValue[];
	onChange: (values: TValue[]) => void;
	variant?: "toolbar" | "field";
	id?: string;
	disabled?: boolean;
	className?: string;
	emptyLabel?: string;
}

export function toFacetOptions<TValue extends string>(
	labels: Record<TValue, string>,
): FacetOption<TValue>[] {
	const options: FacetOption<TValue>[] = [];
	for (const value in labels) {
		options.push({ value, label: labels[value] });
	}
	return options;
}

const MAX_INLINE_CHIPS = 2;

export function FacetMultiSelect<TValue extends string | number>({
	title,
	options,
	selected,
	onChange,
	variant = "toolbar",
	id,
	disabled = false,
	className,
	emptyLabel = "No matches",
}: FacetMultiSelectProps<TValue>) {
	const { contains } = useComboboxFilter({ sensitivity: "base" });
	const selectedOptions = options.filter((option) => selected.includes(option.value));
	const accessibleName =
		selectedOptions.length === 0
			? title
			: `${title}: ${selectedOptions.map((option) => option.label).join(", ")}`;

	return (
		<Combobox
			multiple
			items={options}
			value={selectedOptions}
			isItemEqualToValue={(option, value) => option.value === value.value}
			onValueChange={(next: FacetOption<TValue>[]) => onChange(next.map((option) => option.value))}
			filter={(option, query) =>
				contains(option, query, (o) => (o.description ? `${o.label} ${o.description}` : o.label))
			}
			itemToStringLabel={(option) => option.label}
			disabled={disabled}
		>
			<ComboboxTrigger
				id={id}
				type="button"
				disabled={disabled}
				aria-label={variant === "toolbar" ? accessibleName : title}
				className={cn(
					variant === "toolbar"
						? "h-8 max-w-full border-dashed font-normal"
						: "w-full justify-between font-normal",
					className,
				)}
			>
				{variant === "toolbar" ? (
					<>
						<PlusCircleIcon aria-hidden />
						{title}
						{selectedOptions.length > 0 && (
							<>
								<Separator
									orientation="vertical"
									className="mx-0.5 data-[orientation=vertical]:h-4"
								/>
								{selectedOptions.length > MAX_INLINE_CHIPS ? (
									<Badge variant="secondary" className="rounded-sm px-1 font-normal">
										{selectedOptions.length} selected
									</Badge>
								) : (
									selectedOptions.map((option) => (
										<Badge
											key={option.value}
											variant="secondary"
											className="hidden max-w-36 rounded-sm px-1 font-normal sm:inline-flex"
										>
											{option.icon && <option.icon aria-hidden className={option.iconClassName} />}
											<span className="truncate">{option.label}</span>
										</Badge>
									))
								)}
								{selectedOptions.length <= MAX_INLINE_CHIPS && (
									<Badge variant="secondary" className="rounded-sm px-1 font-normal sm:hidden">
										{selectedOptions.length} selected
									</Badge>
								)}
							</>
						)}
					</>
				) : (
					<>
						<span className="min-w-0 truncate text-left">
							<span>{title}</span>
							{selectedOptions.length > 0 && (
								<span className="text-muted-foreground">
									:{" "}
									{selectedOptions.length === 1 && selectedOptions[0]
										? selectedOptions[0].label
										: `${selectedOptions.length} selected`}
								</span>
							)}
						</span>
						<ComboboxIcon>
							<ChevronsUpDownIcon className="size-4 shrink-0 opacity-50" aria-hidden />
						</ComboboxIcon>
					</>
				)}
			</ComboboxTrigger>

			<ComboboxContent align="start" className="min-w-56" aria-label={`${title} filters`}>
				<ComboboxSearchInput
					placeholder="Search…"
					aria-label={`Search ${title.toLowerCase()} options`}
				/>
				<ComboboxEmpty>{options.length === 0 ? emptyLabel : "No matches"}</ComboboxEmpty>
				{/* The list is its own `role="listbox"`, so labelling the popup around it does not name
				    it: a screen reader arriving on the options hears "listbox" and nothing else. Every
				    other combobox in the app already labels its list; this one did not. */}
				<ComboboxList aria-label={`${title} options`}>
					{(option: FacetOption<TValue>) => (
						<ComboboxItem key={option.value} value={option}>
							<ComboboxItemIndicator />
							{option.icon && (
								<option.icon
									aria-hidden
									className={cn("size-3.5 shrink-0", option.iconClassName)}
								/>
							)}
							<span className="min-w-0 truncate">
								{option.label}
								{option.description && (
									<span className="text-muted-foreground ml-1.5 text-xs">{option.description}</span>
								)}
							</span>
						</ComboboxItem>
					)}
				</ComboboxList>
				{selectedOptions.length > 0 && (
					<>
						<ComboboxSeparator />
						<Button
							variant="ghost"
							size="sm"
							className="h-8 w-full font-normal"
							onClick={() => onChange([])}
						>
							Clear selection
						</Button>
					</>
				)}
			</ComboboxContent>
		</Combobox>
	);
}
