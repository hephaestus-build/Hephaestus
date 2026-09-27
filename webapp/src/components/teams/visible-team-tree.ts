import type { TeamInfo } from "@/api/types.gen";

type TeamNode = Pick<TeamInfo, "id" | "name" | "hidden" | "parentId">;

export interface VisibleTeamTree<TTeam> {
	roots: TTeam[];
	childrenOf: Map<number, TTeam[]>;
}

/**
 * The teams as a member sees them. A hidden team is spliced out rather than taking its subtree with
 * it: its children re-parent onto the nearest visible ancestor, and only a team with no visible
 * ancestor becomes a root. The data is the server's and nothing in the client validates it, so a
 * cycle in `parentId` reads as "no parent" on the way up, and a team reachable only round a cycle is
 * in no tree.
 */
const byName = (first: TeamNode, second: TeamNode) => first.name.localeCompare(second.name);

export function visibleTeamTree<TTeam extends TeamNode>(teams: TTeam[]): VisibleTeamTree<TTeam> {
	const byId = new Map(teams.map((team) => [team.id, team]));
	const visibleParentId = (team: TTeam): number | undefined => {
		const seen = new Set<number>([team.id]);
		let { parentId } = team;
		while (parentId !== undefined && !seen.has(parentId)) {
			seen.add(parentId);
			const parent = byId.get(parentId);
			if (!parent) {
				return undefined;
			}
			if (!parent.hidden) {
				return parent.id;
			}
			parentId = parent.parentId;
		}
		return undefined;
	};

	const visible = teams.filter((team) => !team.hidden);
	const parentOf = new Map(visible.map((team) => [team.id, visibleParentId(team)]));
	const childrenOf = new Map<number, TTeam[]>();
	// Walking down from the roots is what keeps a cycle out: nothing on one is reachable from a root.
	const attach = (team: TTeam) => {
		const children = visible.filter((child) => parentOf.get(child.id) === team.id).sort(byName);
		if (children.length > 0) {
			childrenOf.set(team.id, children);
			for (const child of children) {
				attach(child);
			}
		}
	};
	const roots = visible.filter((team) => parentOf.get(team.id) === undefined).sort(byName);
	for (const root of roots) {
		attach(root);
	}
	return { roots, childrenOf };
}

/**
 * Every team in the tree, parent before child, each named by its path so two "Backend" teams under
 * different parents stay apart: "Platform / Backend".
 */
export function visibleTeamPaths<TTeam extends TeamNode>(
	teams: TTeam[],
): { team: TTeam; path: string }[] {
	const { roots, childrenOf } = visibleTeamTree(teams);
	const walk = (team: TTeam, parentPath?: string): { team: TTeam; path: string }[] => {
		const path = parentPath === undefined ? team.name : `${parentPath} / ${team.name}`;
		return [
			{ team, path },
			...(childrenOf.get(team.id) ?? []).flatMap((child) => walk(child, path)),
		];
	};
	return roots.flatMap((root) => walk(root));
}
