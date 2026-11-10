import { Link } from "@tanstack/react-router";
import { ClipboardCheck, Inbox, Pencil, RotateCcw } from "lucide-react";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import { cn } from "cn";
import type { Practice, PracticeDefinitionOptions } from "@/api/types.gen";
import { canResetToCatalog } from "@/components/admin/practices/CatalogOriginBadge";
import {
	PRACTICE_SETUP_LEVEL_LABELS,
	practiceFormLevel,
} from "@/components/admin/practices/practice-search";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { WorkspacePracticeDefinition } from "@/components/admin/practices/WorkspacePracticeDefinition";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import { Button, buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";

export type WorkspacePracticeState = PanelState<{
	practice: Practice;
	definitionOptions: PracticeDefinitionOptions;
	/** The group's display name, for the inheritance sentence. Absent when unassigned. */
	groupName?: string;
}>;

export interface WorkspacePracticePanelProps {
	workspaceSlug: string;
	state: WorkspacePracticeState;
	nested?: boolean;
	/** Where the level sits, from the drawer. */
	path: LevelPath;
	onResetToCatalog: () => void;
}

export function WorkspacePracticePanel({
	workspaceSlug,
	state,
	nested,
	path,
	onResetToCatalog,
}: WorkspacePracticePanelProps) {
	if (state.status !== "ready") {
		return (
			<>
				<LevelHeader
					nested={nested}
					path={path}
					current={PRACTICE_SETUP_LEVEL_LABELS.practice}
					loading={state.status === "loading"}
				/>
				<DrawerBody>
					{state.status === "loading" ? (
						<PracticeDefinitionSkeleton />
					) : (
						<QueryErrorAlert
							error={state.error}
							title="We could not load this practice"
							onRetry={state.onRetry}
						/>
					)}
				</DrawerBody>
			</>
		);
	}

	const { practice, definitionOptions, groupName } = state;
	const definition = currentDefinition(practice);

	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={PRACTICE_SETUP_LEVEL_LABELS.practice}
				mark={<GroupPill size="lg" slug={practice.groupSlug} name={groupName} />}
				title={definition.name}
				description={<WorkTypeLabel itemKind={practice.kind} />}
			/>

			<DrawerBody>
				<WorkspacePracticeDefinition
					practice={practice}
					definitionOptions={definitionOptions}
					groupName={groupName}
					originActions={
						canResetToCatalog(practice.catalogOrigin) && (
							<div className="mt-2 flex flex-wrap gap-2">
								{practice.catalogOrigin.state === "UPDATE_WAITING" && (
									<Button
										size="sm"
										variant="outline"
										nativeButton={false}
										render={
											<Link
												to="/w/$workspaceSlug/admin/practices/releases"
												params={{ workspaceSlug }}
											/>
										}
									>
										<Inbox /> Catalog updates
									</Button>
								)}
								<Button size="sm" variant="outline" onClick={onResetToCatalog}>
									<RotateCcw /> Reset to catalog
								</Button>
							</div>
						)
					}
				/>
			</DrawerBody>

			<DrawerFooter>
				<Link
					to="/w/$workspaceSlug/admin/practices/reviews/results"
					params={{ workspaceSlug }}
					search={{ practiceSlug: [practice.slug] }}
					className={cn(buttonVariants({ variant: "outline" }), "w-full sm:w-auto")}
				>
					<ClipboardCheck /> See review outcomes
				</Link>
				<DetailStackLink
					entry={practiceFormLevel(practice.slug)}
					className={cn(buttonVariants(), "w-full sm:w-auto")}
				>
					<Pencil /> Edit practice
				</DetailStackLink>
			</DrawerFooter>
		</>
	);
}
