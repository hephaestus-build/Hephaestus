import { Users } from "lucide-react";
import { type ReactNode, useLayoutEffect } from "react";

import type { TeamInfo } from "@/api/types.gen";
import { type Contributor, ContributorGrid } from "@/components/common/ContributorGrid";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { visibleTeamTree } from "./visible-team-tree";

export interface TeamsPageProps {
	teams: TeamInfo[];
	isLoading: boolean;
}

/**
 * Per team, every member that already appears somewhere below it. A team card subtracts this set so
 * a person is listed once — at the deepest team they belong to — rather than repeated up the chain.
 */
function collectDescendantMemberIds(
	visibleTeams: TeamInfo[],
	childrenMap: Map<number, TeamInfo[]>,
	membersByTeamId: Map<number, Set<number>>,
): Map<number, Set<number>> {
	const memo = new Map<number, Set<number>>();

	const collect = (teamId: number): Set<number> => {
		const cached = memo.get(teamId);
		if (cached !== undefined) {
			return cached;
		}
		const children = childrenMap.get(teamId) ?? [];
		const res = new Set<number>();
		for (const child of children) {
			for (const id of membersByTeamId.get(child.id) ?? []) {
				res.add(id);
			}
			for (const id of collect(child.id)) {
				res.add(id);
			}
		}
		memo.set(teamId, res);
		return res;
	};

	for (const team of visibleTeams) {
		collect(team.id);
	}
	return memo;
}

function sortMembers(team: TeamInfo) {
	return [...team.members].sort((a, b) => a.name.localeCompare(b.name));
}

export function TeamsPage({ teams, isLoading }: TeamsPageProps) {
	const visibleTeams = teams.filter((t) => !t.hidden);
	const { roots, childrenOf: childrenMap } = visibleTeamTree(teams);
	const membersByTeamId = new Map(
		visibleTeams.map((t) => [t.id, new Set(t.members.map((member) => member.id))]),
	);
	const descendantMemberIdsMap = collectDescendantMemberIds(
		visibleTeams,
		childrenMap,
		membersByTeamId,
	);

	const getFilteredContributors = (team: TeamInfo): Contributor[] => {
		const exclude = descendantMemberIdsMap.get(team.id) ?? new Set<number>();
		const filtered = sortMembers(team).filter((m) => !exclude.has(m.id));
		return filtered.map((member) => ({
			id: member.id,
			login: member.login,
			name: member.name,
			avatarUrl: member.avatarUrl,
			htmlUrl: member.htmlUrl,
		}));
	};

	const renderTeamNode = (team: TeamInfo, depth = 0) => {
		const children = childrenMap.get(team.id) ?? [];
		const filteredContributors = getFilteredContributors(team);
		const hasDescendantMembers = (descendantMemberIdsMap.get(team.id)?.size ?? 0) > 0;
		const maybeEmptyState =
			filteredContributors.length === 0 && !hasDescendantMembers ? (
				<p className="py-6 text-center text-sm text-muted-foreground">
					No members assigned to this team
				</p>
			) : undefined;
		const content = (
			<>
				<ContributorGrid
					contributors={filteredContributors}
					size="sm"
					layout="compact"
					emptyState={maybeEmptyState}
				/>
				{children.length > 0 && (
					<div className="space-y-5">
						{children.map((child) => renderTeamNode(child, depth + 1))}
					</div>
				)}
			</>
		);

		if (depth > 0) {
			return (
				<section
					key={team.id}
					id={`team-${team.id}`}
					className="min-w-0 space-y-4 border-l pl-3 sm:pl-4"
				>
					<h3 className="text-sm font-semibold">{team.name}</h3>
					{content}
				</section>
			);
		}

		return (
			<Card key={team.id} id={`team-${team.id}`}>
				<CardHeader>
					<CardTitle>
						<h2>{team.name}</h2>
					</CardTitle>
				</CardHeader>
				<CardContent className="min-w-0 space-y-5">{content}</CardContent>
			</Card>
		);
	};

	useLayoutEffect(() => {
		let observer: MutationObserver | null = null;

		const cleanupObserver = () => {
			observer?.disconnect();
			observer = null;
		};

		const scrollToHash = (): boolean => {
			const { hash } = window.location;
			if (!hash) {
				return false;
			}
			const id = hash.slice(1);
			const el = document.getElementById(id);
			if (el) {
				el.scrollIntoView({ behavior: "smooth", block: "center" });
				return true;
			}
			requestAnimationFrame(() => {
				const elNext = document.getElementById(id);
				if (elNext) {
					elNext.scrollIntoView({ behavior: "smooth", block: "center" });
				}
			});
			return false;
		};

		const ensureScroll = () => {
			if (!window.location.hash) {
				cleanupObserver();
				return;
			}

			if (scrollToHash()) {
				cleanupObserver();
				return;
			}

			if (!observer) {
				observer = new MutationObserver(() => {
					if (scrollToHash()) {
						cleanupObserver();
					}
				});
				observer.observe(document.body, { childList: true, subtree: true });
			}
		};

		ensureScroll();
		window.addEventListener("hashchange", ensureScroll);
		return () => {
			cleanupObserver();
			window.removeEventListener("hashchange", ensureScroll);
		};
	}, []);

	let body: ReactNode;
	if (isLoading) {
		body = (
			<div className="space-y-4">
				{["a", "b", "c"].map((id) => (
					<Card key={id}>
						<CardHeader>
							<Skeleton className="h-6 w-1/4" />
						</CardHeader>
						<CardContent>
							<ContributorGrid
								contributors={[]}
								isLoading
								size="sm"
								layout="compact"
								loadingSkeletonCount={4}
							/>
						</CardContent>
					</Card>
				))}
			</div>
		);
	} else if (roots.length > 0) {
		body = <div className="space-y-4">{roots.map((team) => renderTeamNode(team))}</div>;
	} else {
		body = <p className="py-8 text-center text-muted-foreground">No teams found</p>;
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<Users />}
				title="Teams"
				description="See contributors grouped by team and explore their activity."
			/>

			{body}
		</PageLayout>
	);
}
