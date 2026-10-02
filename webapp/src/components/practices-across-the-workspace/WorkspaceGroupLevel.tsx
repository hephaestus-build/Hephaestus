import type { WorkspaceGroupSplit } from "@/api/types.gen";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { StandingBadge, TrendNote } from "@/components/practice-vocabulary/StandingBadge";
import { DrawerBody } from "@/components/ui/drawer";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { useRevealedRows } from "@/hooks/use-revealed-rows";

import type { SplitContext } from "./across-workspace-copy";
import { ProfileLevelLink } from "./ProfileLevelLink";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { WorkspaceSplitBar } from "./WorkspaceSplitBar";

/** How many practices the level lists before it shows more as the reader reaches the end. */
export const PRACTICES_PAGE_SIZE = 20;

export interface WorkspaceGroupLevelProps {
	nested?: boolean;
	path: LevelPath;
	workspaceSlug: string;
	/** The open group; absent while the page loads, or when no group by its slug is shown. */
	group?: WorkspaceGroupSplit;
	/** The split's reference group and its rule; absent while the page loads. */
	context?: SplitContext;
	showWorkspace: boolean;
	isLoading?: boolean;
}

/**
 * One practice group over Practices across the workspace: the reader's own standing and trend in
 * it, the way to their own group on the Practice profile with the group's split under it, and each
 * practice of the group beside how the workspace splits across that practice. Every way out goes
 * to the reader's own profile; nothing here opens another level.
 */
export function WorkspaceGroupLevel({
	nested,
	path,
	workspaceSlug,
	group,
	context,
	showWorkspace,
	isLoading = false,
}: WorkspaceGroupLevelProps) {
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current="Group"
				title={group?.groupName ?? "Practice group"}
				loading={isLoading}
				mark={
					group && (
						<GroupPill
							size="lg"
							slug={group.groupSlug}
							name={group.groupName}
							icon={group.groupIcon}
							color={group.groupColor}
						/>
					)
				}
				chips={
					group && (
						<span className="flex flex-col items-start gap-1.5">
							<StandingBadge standing={group.yourStanding} scope="group" />
							<TrendNote
								direction={group.yourDirection}
								support={group.yourTrendSupport}
								scope="group"
							/>
						</span>
					)
				}
				aside={
					group && (
						<div className="flex w-full flex-col items-stretch gap-3 sm:w-72 sm:items-end">
							<ProfileLevelLink
								workspaceSlug={workspaceSlug}
								groupSlug={group.groupSlug}
								placement="level"
								aria-label={`Open the group ${group.groupName} on your Practice profile`}
							>
								Open the group
							</ProfileLevelLink>
							{showWorkspace && context !== undefined && (
								<div className="w-full">
									<WorkspaceSplitBar
										split={group.split}
										yourStanding={group.yourStanding}
										{...context}
									/>
								</div>
							)}
						</div>
					)
				}
			/>
			<DrawerBody className="flex flex-col gap-4 pt-2">
				{group === undefined && !isLoading ? (
					<Empty variant="outlined">
						<EmptyHeader>
							<EmptyTitle>No practice group here by that name</EmptyTitle>
							<EmptyDescription>
								It may have been removed from this workspace. Close this panel to see every practice
								group.
							</EmptyDescription>
						</EmptyHeader>
					</Empty>
				) : (
					<GroupPractices
						key={group?.groupSlug}
						workspaceSlug={workspaceSlug}
						group={group}
						context={context}
						showWorkspace={showWorkspace}
					/>
				)}
			</DrawerBody>
		</>
	);
}

/** The group's practices, keyed on the group so a new group starts from its first page. */
function GroupPractices({
	workspaceSlug,
	group,
	context,
	showWorkspace,
}: {
	workspaceSlug: string;
	group?: WorkspaceGroupSplit;
	context?: SplitContext;
	showWorkspace: boolean;
}) {
	const rows: ComparisonRow[] = (group?.practices ?? []).map((practice) => ({
		key: practice.practiceSlug,
		name: practice.practiceName,
		subject: <span className="font-medium">{practice.practiceName}</span>,
		yourStanding: practice.yourStanding,
		split: practice.split,
	}));
	const { shown, ...more } = useRevealedRows(rows, PRACTICES_PAGE_SIZE);
	return (
		<WorkspaceComparisonTable
			aria-label={group === undefined ? "Practices" : `Practices of ${group.groupName}`}
			subjectHead="Practice"
			rows={shown}
			scope="practice"
			context={context}
			showWorkspace={showWorkspace}
			isLoading={group === undefined}
			more={more}
			noun="practices"
			empty={{
				title: "No practices here yet",
				description: "Once your workspace reviews a practice in this group, it appears here.",
			}}
			actions={(row) =>
				group && (
					<ProfileLevelLink
						workspaceSlug={workspaceSlug}
						groupSlug={group.groupSlug}
						practiceSlug={row.key}
						placement="row"
						aria-label={`View practice ${row.name}`}
					>
						View practice
					</ProfileLevelLink>
				)
			}
		/>
	);
}
