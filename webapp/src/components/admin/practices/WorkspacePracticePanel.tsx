import { Link } from "@tanstack/react-router";
import { ClipboardCheck, Inbox, Pencil, RotateCcw } from "lucide-react";
import { useId } from "react";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import { cn } from "cn";
import type { Practice, PracticeDefinitionOptions } from "@/api/types.gen";
import {
	PRACTICE_SETUP_LEVEL_LABELS,
	practiceFormLevel,
} from "@/components/admin/practices/practice-search";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { canResetToCatalog } from "@/components/admin/practices/WorkspaceCatalogOriginBadge";
import {
	type EditInHephaestusControl,
	WorkspaceEditInHephaestusDialog,
} from "@/components/admin/practices/WorkspaceEditInHephaestusDialog";
import {
	type MoveToRepositoryControl,
	WorkspaceMoveToRepositoryDialog,
} from "@/components/admin/practices/WorkspaceMoveToRepositoryDialog";
import { WorkspacePracticeDefinition } from "@/components/admin/practices/WorkspacePracticeDefinition";
import { WorkspacePracticeEditedInHephaestusNote } from "@/components/admin/practices/WorkspacePracticeEditedInHephaestusNote";
import { WorkspacePracticeEditedInNote } from "@/components/admin/practices/WorkspacePracticeEditedInNote";
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
	/** Edit in Hephaestus, for a practice edited in the practice repository. */
	editInHephaestus: EditInHephaestusControl;
	/** Move to repository, for a practice edited in Hephaestus. */
	moveToRepository: MoveToRepositoryControl;
}

export function WorkspacePracticePanel({
	workspaceSlug,
	state,
	nested,
	path,
	onResetToCatalog,
	editInHephaestus,
	moveToRepository,
}: WorkspacePracticePanelProps) {
	const editLinkId = useId();
	const moveId = useId();
	const editInHephaestusId = useId();

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
	// Its folder in the practice repository holds its definition and group, so nothing here may change
	// them: the server refuses an edit, a reset and an accepted catalog update for it.
	const editedInRepository = practice.editedIn === "REPOSITORY";
	const updateWaiting = practice.catalogOrigin.state === "UPDATE_WAITING";
	const resettable = !editedInRepository && canResetToCatalog(practice.catalogOrigin);

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
				<div className="space-y-6">
					{editedInRepository && (
						<WorkspacePracticeEditedInNote
							workspaceSlug={workspaceSlug}
							editInHephaestusId={editInHephaestusId}
							repositoryName={editInHephaestus.repositoryName}
							sourceCommit={practice.currentVersion.sourceCommit}
							onEditInHephaestus={() => editInHephaestus.onConfirmingChange(true)}
						/>
					)}
					<WorkspacePracticeDefinition
						practice={practice}
						definitionOptions={definitionOptions}
						groupName={groupName}
						originActions={
							(updateWaiting || resettable) && (
								<div className="mt-2 flex flex-wrap gap-2">
									{updateWaiting && (
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
									{resettable && (
										<Button size="sm" variant="outline" onClick={onResetToCatalog}>
											<RotateCcw /> Reset to catalog
										</Button>
									)}
								</div>
							)
						}
					/>
					{/* Absent while the practice repository is unknown: it offers nothing that may not be. */}
					{!editedInRepository && moveToRepository.repository !== undefined && (
						<WorkspacePracticeEditedInHephaestusNote
							workspaceSlug={workspaceSlug}
							repository={moveToRepository.repository}
							moveId={moveId}
							onMoveToRepository={moveToRepository.onOpen}
						/>
					)}
				</div>
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
				{!editedInRepository && (
					<DetailStackLink
						id={editLinkId}
						entry={practiceFormLevel(practice.slug)}
						className={cn(buttonVariants(), "w-full sm:w-auto")}
					>
						<Pencil /> Edit practice
					</DetailStackLink>
				)}
			</DrawerFooter>

			{/* Outside the notice, which leaves when the practice becomes editable: the request ends in
			    the same render that shows Edit practice, so the dialog's final focus lands there. */}
			<WorkspaceEditInHephaestusDialog
				practiceName={definition.name}
				// A practice that is editable never asks: the hook owns the request, and this only keeps a
				// confirmation from showing over a practice that is no longer edited in the repository.
				control={{
					...editInHephaestus,
					confirming: editInHephaestus.confirming && editedInRepository,
				}}
				finalFocus={() => document.getElementById(editLinkId) ?? true}
			/>
			{/* Outside the notes for the same reason: the move ends in the render that shows the practice
			    read-only, so focus lands on Edit in Hephaestus, and a cancel on Move to repository. */}
			<WorkspaceMoveToRepositoryDialog
				practiceName={definition.name}
				practiceSlug={practice.slug}
				// A practice edited in the repository never asks to move there.
				control={
					editedInRepository ? { ...moveToRepository, request: undefined } : moveToRepository
				}
				finalFocus={() =>
					document.getElementById(editInHephaestusId) ?? document.getElementById(moveId) ?? true
				}
			/>
		</>
	);
}
