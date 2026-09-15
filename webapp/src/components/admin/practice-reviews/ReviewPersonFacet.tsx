import { UserRoundIcon } from "lucide-react";

import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Combobox,
	ComboboxContent,
	ComboboxEmpty,
	ComboboxItem,
	ComboboxItemIndicator,
	ComboboxList,
	ComboboxSearchInput,
	ComboboxSeparator,
	ComboboxTrigger,
	useComboboxFilter,
} from "@/components/ui/combobox";
import { Separator } from "@/components/ui/separator";
import { getInitials } from "@/lib/avatar";

export const MEMBER_PAGE_SIZE = 100;

export interface PersonOption {
	userId: number;
	label: string;
	secondary?: string;
}

export interface ReviewPeople extends FacetSource<PersonOption> {
	capped: boolean;
}

export interface ReviewPersonFacetProps {
	title: string;
	people: ReviewPeople;
	selected: number | undefined;
	onChange: (userId: number | undefined) => void;
	fallbackName?: string;
}

export function ReviewPersonFacet({
	title,
	people,
	selected,
	onChange,
	fallbackName,
}: ReviewPersonFacetProps) {
	const { contains } = useComboboxFilter({ sensitivity: "base" });
	const { options, capped } = people;
	const selectedOption =
		options.find((option) => option.userId === selected) ??
		(selected != null ? { userId: selected, label: fallbackName ?? `#${selected}` } : null);

	return (
		<Combobox
			items={options}
			value={selectedOption}
			isItemEqualToValue={(option: PersonOption, value: PersonOption) =>
				option.userId === value.userId
			}
			onValueChange={(next: PersonOption | null) => onChange(next?.userId)}
			filter={(option: PersonOption, query) =>
				contains(option, query, (o) => (o.secondary ? `${o.label} ${o.secondary}` : o.label))
			}
			itemToStringLabel={(option: PersonOption) => option.label}
			disabled={people.isLoading}
		>
			<ComboboxTrigger
				type="button"
				disabled={people.isLoading}
				aria-label={selectedOption ? `${title}: ${selectedOption.label}` : title}
				className="h-8 max-w-full border-dashed font-normal"
			>
				<UserRoundIcon aria-hidden />
				{title}
				{selectedOption && (
					<>
						<Separator orientation="vertical" className="mx-0.5 data-[orientation=vertical]:h-4" />
						<Badge variant="secondary" className="max-w-36 rounded-sm px-1 font-normal">
							<span className="truncate">{selectedOption.label}</span>
						</Badge>
					</>
				)}
			</ComboboxTrigger>

			<ComboboxContent align="start" className="min-w-64" aria-label={`${title} filter`}>
				<ComboboxSearchInput
					placeholder="Search people…"
					aria-label={`Search ${title.toLowerCase()} options`}
				/>
				<ComboboxEmpty>
					{people.isError
						? "Could not load people"
						: options.length === 0
							? "No people in this workspace"
							: "No matches"}
				</ComboboxEmpty>
				<ComboboxList aria-label={`${title} options`}>
					{(option: PersonOption) => (
						<ComboboxItem key={option.userId} value={option}>
							<ComboboxItemIndicator />
							<Avatar className="size-5 shrink-0">
								<AvatarFallback className="text-2xs">{getInitials(option.label)}</AvatarFallback>
							</Avatar>
							<span className="min-w-0 truncate">
								{option.label}
								{option.secondary && (
									<span className="ml-1.5 text-xs text-muted-foreground">{option.secondary}</span>
								)}
							</span>
						</ComboboxItem>
					)}
				</ComboboxList>
				{capped && (
					<p className="border-t px-2 py-1.5 text-xs text-muted-foreground">
						Showing the first {MEMBER_PAGE_SIZE} members. Search looks only at these — to filter by
						someone further down the list, open their work and follow the link from a row.
					</p>
				)}
				{selectedOption && (
					<>
						<ComboboxSeparator />
						<Button
							variant="ghost"
							size="sm"
							className="h-8 w-full font-normal"
							onClick={() => onChange(undefined)}
						>
							Clear selection
						</Button>
					</>
				)}
			</ComboboxContent>
		</Combobox>
	);
}
