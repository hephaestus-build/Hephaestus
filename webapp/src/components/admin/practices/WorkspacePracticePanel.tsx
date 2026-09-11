import { Pencil } from "lucide-react";

import { cn } from "cn";
import type { Practice, PracticeDefinitionOptions } from "@/api/types.gen";
import { PracticeDefinitionPreview } from "@/components/admin/practice-adoption/PracticeDefinitionPreview";
import { GroupPill } from "@/components/admin/practice-catalog/GroupPill";
import { CatalogOriginBadge } from "@/components/admin/practices/CatalogOriginBadge";
import { practiceFormLevel } from "@/components/admin/practices/practice-search";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailDrawerHeader } from "@/components/core/detail-drawer/DetailDrawerHeader";
import { DetailStackLink } from "@/components/core/detail-drawer/DetailStackLink";
import { AUTONOMY_DEFS } from "@/components/practice-vocabulary/autonomy-defs";
import { AutonomySourceNote } from "@/components/practice-vocabulary/AutonomySourceNote";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import { buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerDescription, DrawerFooter, DrawerTitle } from "@/components/ui/drawer";
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
	state: WorkspacePracticeState;
	nested?: boolean;
}

/**
 * A workspace practice, read-only, so that opening one is not the same act as editing it.
 *
 * Renders the same {@link PracticeDefinitionPreview} the catalog drawer uses — `Practice` is
 * structurally a `CuratedPracticeDefinition` — so a practice reads identically whether it was met in
 * the catalog or in the workspace's own tree. Editing stays a route: a form that must ask before
 * discarding work is not a dismissible surface.
 */
export function WorkspacePracticePanel({ state, nested }: WorkspacePracticePanelProps) {
	if (state.status !== "ready") {
		return (
			<>
				<DetailDrawerHeader nested={nested}>
					<DrawerTitle>Practice</DrawerTitle>
				</DetailDrawerHeader>
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
			<DetailDrawerHeader nested={nested}>
				<GroupPill size="lg" slug={practice.groupSlug} name={groupName} />
				<div className="min-w-0 flex-1 space-y-1">
					<DrawerTitle className="break-words">{practice.name}</DrawerTitle>
					<DrawerDescription>
						<WorkTypeLabel artifactKind={practice.artifactKind} />
					</DrawerDescription>
					{/* Under the title, not beside it: provenance is a sentence of its own, and a second
					    text column would take the width the title needs. */}
					<CatalogOriginBadge origin={practice.catalogOrigin} kind="practice" className="mt-1.5" />
				</div>
			</DetailDrawerHeader>

			<DrawerBody className="space-y-6">
				<ItemGroup className="gap-2">
					<Item variant="muted" size="sm" role="listitem">
						<ItemContent>
							<ItemTitle className="flex flex-wrap items-center gap-2">
								<StatusBadge def={autonomy} />
								<AutonomySourceNote
									source={autonomySource}
									className="text-muted-foreground text-xs font-normal"
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
