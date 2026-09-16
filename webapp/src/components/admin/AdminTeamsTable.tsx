import { Search, Users } from "lucide-react";

import type { LabelInfo, TeamInfo } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Skeleton } from "@/components/ui/skeleton";

import { TeamTree } from "./teams/TeamTree";

export interface TeamsTableProps {
	teams: TeamInfo[];
	isLoading?: boolean;
	error?: unknown;
	onRetry?: () => void;
	search: string;
	onSearchChange: (search: string) => void;
	onHideTeam: (teamId: number, hidden: boolean) => Promise<void>;
	onToggleRepositoryVisibility: (
		teamId: number,
		repositoryId: number,
		hidden: boolean,
	) => Promise<void>;
	onAddLabelToTeam?: (teamId: number, repositoryId: number, label: string) => Promise<void>;
	onRemoveLabelFromTeam?: (teamId: number, labelId: number) => Promise<void>;
}

/**
 * The ids the tree still shows: a team whose own name matches, plus every ancestor down to it, so a
 * deep match stays reachable inside its branch. An empty search shows everything.
 */
function teamsMatchingSearch(
	search: string,
	teams: TeamInfo[],
	roots: TeamInfo[],
	childrenMap: Map<number, TeamInfo[]>,
): Set<number> {
	const normalizedSearch = search.trim().toLowerCase();
	if (!normalizedSearch) {
		return new Set(teams.map((t) => t.id));
	}

	const result = new Set<number>();
	const memo = new Map<number, boolean>();
	const hasMatchInSubtree = (team: TeamInfo): boolean => {
		const cached = memo.get(team.id);
		if (cached !== undefined) {
			return cached;
		}
		if (team.name.toLowerCase().includes(normalizedSearch)) {
			memo.set(team.id, true);
			return true;
		}
		for (const child of childrenMap.get(team.id) ?? []) {
			if (hasMatchInSubtree(child)) {
				memo.set(team.id, true);
				return true;
			}
		}
		memo.set(team.id, false);
		return false;
	};

	const traverse = (node: TeamInfo) => {
		if (!hasMatchInSubtree(node)) {
			return;
		}
		result.add(node.id);
		for (const child of childrenMap.get(node.id) ?? []) {
			traverse(child);
		}
	};
	for (const root of roots) {
		traverse(root);
	}
	return result;
}

export function AdminTeamsTable({
	teams,
	isLoading = false,
	error,
	onRetry,
	search,
	onSearchChange,
	onHideTeam,
	onToggleRepositoryVisibility,
	onAddLabelToTeam,
	onRemoveLabelFromTeam,
}: TeamsTableProps) {
	const allTeamsById = new Map(teams.map((t) => [t.id, t]));

	const childrenMap = new Map<number, TeamInfo[]>();
	for (const t of teams) {
		const pid = t.parentId;
		if (pid !== undefined && allTeamsById.has(pid)) {
			const siblings = childrenMap.get(pid) ?? [];
			siblings.push(t);
			childrenMap.set(pid, siblings);
		}
	}
	for (const siblings of childrenMap.values()) {
		siblings.sort((a, b) => a.name.localeCompare(b.name));
	}

	// A team whose parent is missing from the page is a root here rather than an orphan nobody renders.
	const rootsAll = teams
		.filter((t) => t.parentId === undefined || !allTeamsById.has(t.parentId))
		.sort((a, b) => a.name.localeCompare(b.name));

	const displaySet = teamsMatchingSearch(search, teams, rootsAll, childrenMap);

	const repoLabelCatalog = new Map<number, Map<number, LabelInfo>>();
	for (const t of teams) {
		for (const repo of t.repositories) {
			const byId = repoLabelCatalog.get(repo.id) ?? new Map<number, LabelInfo>();
			for (const lbl of repo.labels ?? []) {
				byId.set(lbl.id, lbl);
			}
			repoLabelCatalog.set(repo.id, byId);
		}
	}

	const getCatalogLabels = (repoId: number): LabelInfo[] => {
		const byId = repoLabelCatalog.get(repoId);
		if (!byId) {
			return [];
		}
		return [...byId.values()].sort((a, b) => a.name.localeCompare(b.name));
	};

	const header = (
		<PageHeader
			icon={<Users />}
			title="Teams"
			description="Manage which synced teams and repositories participate in this workspace."
		/>
	);

	if (error) {
		return (
			<PageLayout>
				{header}
				<QueryErrorAlert error={error} title="Couldn't load teams" onRetry={onRetry} />
			</PageLayout>
		);
	}

	if (isLoading) {
		return (
			<PageLayout>
				{header}
				<div className="flex items-center justify-between">
					<Skeleton className="h-10 w-64" />
					<Skeleton className="h-10 w-32" />
				</div>
				<div className="space-y-4">
					{["a", "b", "c", "d"].map((id) => (
						<Skeleton key={`loading-${id}`} className="h-32" />
					))}
				</div>
			</PageLayout>
		);
	}

	return (
		<PageLayout>
			{header}
			<div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
				<InputGroup className="w-full sm:max-w-md">
					<InputGroupAddon>
						<Search />
					</InputGroupAddon>
					<InputGroupInput
						aria-label="Search teams"
						placeholder="Search teams..."
						value={search}
						onChange={(e) => onSearchChange(e.target.value)}
					/>
				</InputGroup>
			</div>

			{rootsAll.filter((t) => displaySet.has(t.id)).length === 0 ? (
				<div className="py-12 text-center">
					<Users className="mx-auto mb-4 h-12 w-12 text-muted-foreground" />
					<h2 className="mb-2 text-lg font-medium">No teams found</h2>
					<p className="text-muted-foreground">
						{search ? "Try different search terms." : "No teams available."}
					</p>
				</div>
			) : (
				<div className="space-y-4">
					{rootsAll
						.filter((t) => displaySet.has(t.id))
						.map((team) => (
							<TeamTree
								key={team.id}
								team={team}
								childrenMap={childrenMap}
								displaySet={displaySet}
								onToggleVisibility={(teamId, hidden) => onHideTeam(teamId, hidden)}
								onToggleRepositoryVisibility={onToggleRepositoryVisibility}
								onAddLabel={onAddLabelToTeam}
								onRemoveLabel={onRemoveLabelFromTeam}
								getCatalogLabels={getCatalogLabels}
							/>
						))}
				</div>
			)}
		</PageLayout>
	);
}
