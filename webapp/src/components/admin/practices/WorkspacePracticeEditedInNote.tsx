import { FolderGit2 } from "lucide-react";

import { PracticeRepositoryLink } from "@/components/admin/practices/WorkspacePracticeRepositoryCard";
import { Button } from "@/components/ui/button";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";

export interface WorkspacePracticeEditedInNoteProps {
	workspaceSlug: string;
	/** The practice repository's provider path, such as `acme/practices`. Absent while unknown. */
	repositoryName?: string;
	/** The commit of the practice repository that the current version was read at. */
	sourceCommit: string | null;
	/** The `id` of Edit in Hephaestus, so focus can land on it after Move to repository. */
	editInHephaestusId?: string;
	/** Opens the confirmation, `WorkspaceEditInHephaestusDialog`. */
	onEditInHephaestus: () => void;
}

/**
 * Says that a practice is read-only here because its folder in the practice repository holds its
 * definition and group, and offers the way back. The confirmation is not part of the note: the
 * note leaves when the practice becomes editable, and the dialog has to outlive it to hand focus
 * to what replaced it.
 */
export function WorkspacePracticeEditedInNote({
	workspaceSlug,
	repositoryName,
	sourceCommit,
	editInHephaestusId,
	onEditInHephaestus,
}: WorkspacePracticeEditedInNoteProps) {
	return (
		<Item variant="muted" size="sm">
			<ItemMedia variant="icon">
				<FolderGit2 aria-hidden />
			</ItemMedia>
			<ItemContent>
				<ItemTitle>Edited in {repositoryName ?? "the practice repository"}</ItemTitle>
				<ItemDescription className="line-clamp-none">
					Its folder there holds its definition and group, so you cannot change them here. You can
					still change its feedback mode and position.
					{sourceCommit !== null && (
						<>
							{" "}
							This version was read at commit <code>{sourceCommit.slice(0, 7)}</code>.
						</>
					)}{" "}
					See the{" "}
					<PracticeRepositoryLink workspaceSlug={workspaceSlug}>
						practice repository setting
					</PracticeRepositoryLink>
					.
				</ItemDescription>
			</ItemContent>
			<ItemActions>
				<Button id={editInHephaestusId} size="sm" variant="outline" onClick={onEditInHephaestus}>
					Edit in Hephaestus
				</Button>
			</ItemActions>
		</Item>
	);
}
