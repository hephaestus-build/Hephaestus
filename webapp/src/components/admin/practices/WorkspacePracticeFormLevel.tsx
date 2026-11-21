import { type ReactNode, useRef } from "react";

import { PRACTICE_SETUP_LEVEL_LABELS } from "@/components/admin/practices/practice-search";
import {
	type EditInHephaestusControl,
	WorkspaceEditInHephaestusDialog,
} from "@/components/admin/practices/WorkspaceEditInHephaestusDialog";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";

export interface WorkspacePracticeFormLevelProps {
	creating: boolean;
	/**
	 * The practice's name when its folder in the practice repository holds its definition. The level
	 * then has no editor, so its header names the practice and says where it is edited instead of
	 * offering to edit it.
	 */
	editedInRepositoryName?: string;
	/**
	 * Edit in Hephaestus for the practice this level edits. The level holds its confirmation because
	 * the level stays while its content changes from the read-only notice to the form.
	 */
	editInHephaestus?: { practiceName: string; control: EditInHephaestusControl };
	nested?: boolean;
	/** Where the level sits, from the host's `levelPathAt`. */
	path: LevelPath;
	children: ReactNode;
}

/** The practice editor as a drawer level. Guarded — `webapp/DESIGN.md` § Guarded levels. */
export function WorkspacePracticeFormLevel({
	creating,
	editedInRepositoryName,
	editInHephaestus,
	nested,
	path,
	children,
}: WorkspacePracticeFormLevelProps) {
	const editedInRepository = editedInRepositoryName !== undefined;
	const titleRef = useRef<HTMLHeadingElement>(null);

	let description = "Update this practice’s review rules and developer guidance.";
	if (editedInRepository) {
		description = "Edited in the practice repository";
	} else if (creating) {
		description = "Define a way of working and choose how it is reviewed.";
	}
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={
					editedInRepository
						? PRACTICE_SETUP_LEVEL_LABELS.practice
						: PRACTICE_SETUP_LEVEL_LABELS[creating ? "practice-new" : "practice-edit"]
				}
				title={editedInRepositoryName}
				description={description}
				titleRef={titleRef}
			/>
			{children}
			{editInHephaestus && (
				// The request ends in the same render that swaps the notice for the form, so the dialog's
				// final focus lands on the title, at the top of the form. Cancelled, the dialog returns
				// focus to the notice's button.
				<WorkspaceEditInHephaestusDialog
					practiceName={editInHephaestus.practiceName}
					control={{
						...editInHephaestus.control,
						confirming: editInHephaestus.control.confirming && editedInRepository,
					}}
					finalFocus={() => (editedInRepository ? true : (titleRef.current ?? true))}
				/>
			)}
		</>
	);
}
