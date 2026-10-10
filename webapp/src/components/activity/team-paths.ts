import type { ActivityTeam } from "@/api/types.gen";

export interface TeamOption {
	/** The slug, or the empty string for everyone. */
	key: string;
	/** The team's path through its parents: "Platform / Payments". */
	label: string;
}

/** Each team by its path through the teams the page shows, in path order. */
export function teamPaths(teams: readonly ActivityTeam[]): TeamOption[] {
	const byId = new Map(teams.map((team) => [team.id, team]));
	const pathOf = (team: ActivityTeam, seen: Set<number>): string => {
		const parent = team.parentId === undefined ? undefined : byId.get(team.parentId);
		if (parent === undefined || seen.has(parent.id)) {
			return team.name;
		}
		return `${pathOf(parent, seen.add(team.id))} / ${team.name}`;
	};
	return teams
		.map((team) => ({ key: team.key, label: pathOf(team, new Set()) }))
		.sort((a, b) => a.label.localeCompare(b.label));
}
