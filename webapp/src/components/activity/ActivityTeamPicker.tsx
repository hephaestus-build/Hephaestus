import { ChevronsUpDownIcon, UsersIcon } from "lucide-react";

import type { ActivityTeam } from "@/api/types.gen";
import {
	Combobox,
	ComboboxContent,
	ComboboxEmpty,
	ComboboxIcon,
	ComboboxItem,
	ComboboxItemIndicator,
	ComboboxList,
	ComboboxSearchInput,
	ComboboxTrigger,
} from "@/components/ui/combobox";
import { hasText } from "@/lib/text";

import { type TeamOption, teamPaths } from "./team-paths";

export interface ActivityTeamPickerProps {
	/** The teams workspace activity shows; undefined while they load or when they failed. */
	teams: readonly ActivityTeam[] | undefined;
	/** The teams could not load. */
	failed?: boolean;
	/** The team's slug; undefined is everyone. */
	value: string | undefined;
	onChange: (team: string | undefined) => void;
}

const EVERYONE: TeamOption = { key: "", label: "Everyone" };

/**
 * Whose activity the page counts: everyone, or one team and its sub-teams, found by typing. A team
 * reads by its path, so two "Backend" teams under different parents stay apart. A workspace with no
 * teams has nothing to pick.
 */
export function ActivityTeamPicker({
	teams,
	failed = false,
	value,
	onChange,
}: ActivityTeamPickerProps) {
	if (teams?.length === 0 && value === undefined) {
		return null;
	}
	const options = [EVERYONE, ...teamPaths(teams ?? [])];
	const selected = options.find((option) => option.key === (value ?? "")) ?? {
		key: value ?? "",
		label: value ?? EVERYONE.label,
	};
	return (
		<Combobox
			items={options}
			value={selected}
			isItemEqualToValue={(option: TeamOption, current: TeamOption) => option.key === current.key}
			onValueChange={(next: TeamOption | null) => {
				const key = next?.key;
				onChange(hasText(key) ? key : undefined);
			}}
			itemToStringLabel={(option: TeamOption) => option.label}
		>
			<ComboboxTrigger
				type="button"
				aria-label={`Team: ${selected.label}`}
				className="w-full justify-between font-normal sm:w-56"
			>
				<UsersIcon aria-hidden className="text-muted-foreground" />
				<span className="min-w-0 flex-1 truncate text-left">{selected.label}</span>
				<ComboboxIcon>
					<ChevronsUpDownIcon className="size-4 opacity-50" aria-hidden />
				</ComboboxIcon>
			</ComboboxTrigger>
			<ComboboxContent align="start" className="min-w-64" aria-label="Team">
				<ComboboxSearchInput placeholder="Search teams…" aria-label="Search teams" />
				<ComboboxEmpty>{emptyText(teams, failed)}</ComboboxEmpty>
				<ComboboxList aria-label="Teams">
					{(option: TeamOption) => (
						<ComboboxItem key={option.key} value={option}>
							<ComboboxItemIndicator />
							<span className="min-w-0 truncate">{option.label}</span>
						</ComboboxItem>
					)}
				</ComboboxList>
			</ComboboxContent>
		</Combobox>
	);
}

function emptyText(teams: readonly ActivityTeam[] | undefined, failed: boolean): string {
	if (failed) {
		return "We could not load teams";
	}
	return teams === undefined ? "Loading teams…" : "No team matches";
}
