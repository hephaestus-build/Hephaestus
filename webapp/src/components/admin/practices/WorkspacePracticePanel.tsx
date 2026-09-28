import { Link } from "@tanstack/react-router";
import { ClipboardCheck, Pencil } from "lucide-react";

import { cn } from "cn";
import type { Practice, PracticeDefinitionOptions } from "@/api/types.gen";
import { practiceLevel } from "@/components/admin/practice-reviews/review-levels";
import { CatalogOriginBadge } from "@/components/admin/practices/CatalogOriginBadge";
import {
	PRACTICE_SETUP_LEVEL_LABELS,
	practiceFormLevel,
} from "@/components/admin/practices/practice-search";
import { PracticeDefinitionPreview } from "@/components/admin/practices/PracticeDefinitionPreview";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { AUTONOMY_DEFS } from "@/components/practice-vocabulary/autonomy-defs";
import { AutonomySourceNote } from "@/components/practice-vocabulary/AutonomySourceNote";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import { buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from "@/components/ui/item";
import { Separator } from "@/components/ui/separator";
import { autonomySourceOf } from "@/lib/practice-autonomy";

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
}

/**
 * A workspace practice, read-only, so that opening one is not the same act as editing it.
 *
 * Renders the same {@link PracticeDefinitionPreview} the catalog drawer uses — `Practice` is
 * structurally a `CuratedPracticeDefinition` — so a practice reads identically whether it was met in
 * the catalog or in the workspace's own tree. Editing stays a route: a form that must ask before
 * discarding work is not a dismissible surface.
 */
export function WorkspacePracticePanel({
	workspaceSlug,
	state,
	nested,
	path,
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
							title="Couldn't load this practice"
							onRetry={state.onRetry}
						/>
					)}
				</DrawerBody>
			</>
		);
	}

	const { practice, definitionOptions, groupName } = state;
	const autonomy = AUTONOMY_DEFS[practice.autonomy.effective];
	const autonomySource = autonomySourceOf(practice.autonomy, groupName ?? null);

	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={PRACTICE_SETUP_LEVEL_LABELS.practice}
				mark={<GroupPill size="lg" slug={practice.groupSlug} name={groupName} />}
				title={practice.name}
				chips={<CatalogOriginBadge origin={practice.catalogOrigin} kind="practice" />}
				description={<WorkTypeLabel artifactKind={practice.artifactKind} />}
			/>

			<DrawerBody className="space-y-6">
				<ItemGroup className="gap-2">
					<Item variant="muted" size="sm" role="listitem">
						<ItemContent>
							<ItemTitle className="flex flex-wrap items-center gap-2">
								<StatusBadge def={autonomy} />
								<AutonomySourceNote
									source={autonomySource}
									className="text-xs font-normal text-muted-foreground"
								/>
							</ItemTitle>
							<ItemDescription className="line-clamp-none">{autonomy.description}</ItemDescription>
						</ItemContent>
					</Item>
				</ItemGroup>

				<Separator />

				<PracticeDefinitionPreview definition={practice} options={definitionOptions} />
			</DrawerBody>

			<DrawerFooter>
				<Link
					to="/w/$workspaceSlug/admin/practices/reviews"
					params={{ workspaceSlug }}
					search={detailSearch(practiceLevel(practice.slug))}
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
