import { FolderInput } from "lucide-react";
import { type ReactNode, useId } from "react";

import type { PracticeRepository } from "@/api/types.gen";
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
import { hasText } from "@/lib/text";

export interface WorkspacePracticeEditedInHephaestusNoteProps {
	workspaceSlug: string;
	/** The workspace's practice repository, or `null` when it has none. */
	repository: PracticeRepository | null;
	/** The `id` of Move to repository, so focus can come back to it. */
	moveId?: string;
	/** Opens the confirmation, `WorkspaceMoveToRepositoryDialog`. */
	onMoveToRepository: () => void;
}

/** Why Move to repository cannot run now, or `undefined` when it can. */
function unavailableReason(
	workspaceSlug: string,
	repository: PracticeRepository | null,
): ReactNode {
	if (repository === null) {
		return (
			<>
				To change it in a repository instead, first set the{" "}
				<PracticeRepositoryLink workspaceSlug={workspaceSlug}>
					practice repository
				</PracticeRepositoryLink>
				.
			</>
		);
	}
	// The server reads the folder from the head of the default branch of a monitored repository.
	if (!hasText(repository.nameWithOwner)) {
		return (
			<>
				The workspace no longer monitors the practice repository. Choose another in the{" "}
				<PracticeRepositoryLink workspaceSlug={workspaceSlug}>
					practice repository setting
				</PracticeRepositoryLink>
				.
			</>
		);
	}
	if (!hasText(repository.defaultBranch)) {
		return `The default branch of ${repository.nameWithOwner} is not known yet. Try again once the repository has synced.`;
	}
	return undefined;
}

/**
 * Says that a practice is edited in Hephaestus and offers Move to repository, the reverse of
 * `WorkspacePracticeEditedInNote`. When the move cannot run, an admin can still make it possible,
 * so the action stays on screen, unavailable, with the reason and the way to the setting.
 */
export function WorkspacePracticeEditedInHephaestusNote({
	workspaceSlug,
	repository,
	moveId,
	onMoveToRepository,
}: WorkspacePracticeEditedInHephaestusNoteProps) {
	const reasonId = useId();
	const reason = unavailableReason(workspaceSlug, repository);
	const unavailable = reason !== undefined;
	return (
		<Item variant="outline" size="sm">
			<ItemMedia variant="icon">
				<FolderInput aria-hidden />
			</ItemMedia>
			<ItemContent>
				<ItemTitle>Edited in Hephaestus</ItemTitle>
				<ItemDescription id={reasonId} className="line-clamp-none">
					{unavailable || repository === null ? (
						reason
					) : (
						<>
							Move it to {repository.nameWithOwner} to change its definition and group there.
						</>
					)}
				</ItemDescription>
			</ItemContent>
			<ItemActions>
				<Button
					id={moveId}
					size="sm"
					variant="outline"
					disabled={unavailable}
					// Focusable while unavailable, so a keyboard reaches the reason it is described by.
					focusableWhenDisabled={unavailable}
					aria-describedby={unavailable ? reasonId : undefined}
					onClick={onMoveToRepository}
				>
					Move to repository
				</Button>
			</ItemActions>
		</Item>
	);
}
