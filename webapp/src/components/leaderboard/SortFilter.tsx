import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";

export type LeaderboardSortType = "SCORE" | "LEAGUE_POINTS";

const SORT_OPTIONS: { value: LeaderboardSortType; label: string }[] = [
	{ value: "SCORE", label: "Score" },
	{ value: "LEAGUE_POINTS", label: "League Points" },
];

export interface SortFilterProps {
	onSortChange?: (sort: LeaderboardSortType) => void;
	selectedSort?: LeaderboardSortType;
	leaguesEnabled?: boolean;
}

export function SortFilter({
	onSortChange,
	selectedSort = "SCORE",
	leaguesEnabled = true,
}: SortFilterProps) {
	const visibleOptions = leaguesEnabled
		? SORT_OPTIONS
		: SORT_OPTIONS.filter((o) => o.value !== "LEAGUE_POINTS");

	return (
		<div className="space-y-1.5">
			<Label id="sort-label" htmlFor="sort">
				Sort by
			</Label>
			<Select
				value={selectedSort}
				onValueChange={(value) => {
					if (value) {
						onSortChange?.(value);
					}
				}}
				items={visibleOptions}
			>
				<SelectTrigger id="sort" className="w-full">
					<SelectValue placeholder="Sort by" />
				</SelectTrigger>
				<SelectContent aria-labelledby="sort-label">
					{visibleOptions.map((option) => (
						<SelectItem key={option.value} value={option.value}>
							{option.label}
						</SelectItem>
					))}
				</SelectContent>
			</Select>
		</div>
	);
}
