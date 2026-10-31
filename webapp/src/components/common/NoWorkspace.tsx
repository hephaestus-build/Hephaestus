import { Link } from "@tanstack/react-router";
import { Folders, PlusIcon } from "lucide-react";

import { EmptyState } from "@/components/common/EmptyState";
import { buttonVariants } from "@/components/ui/button";

/** Whether the reader may create a workspace on this instance, or only an instance admin may. */
export type WorkspaceCreation = "allowed" | "instance-admin-only";

export interface NoWorkspaceProps {
	/** Level 1 where this is the whole page; 2 where it sits under something else, such as the sidebar. */
	headingLevel?: 1 | 2;
	/** Undefined while the creation policy is unread: the page then offers no step it may take back. */
	creation: WorkspaceCreation | undefined;
}

const NOT_A_MEMBER = "You are not a member of any workspace yet.";

export function NoWorkspace({ headingLevel = 1, creation }: NoWorkspaceProps) {
	return (
		<EmptyState
			variant="plain"
			icon={<Folders />}
			title="No workspace"
			headingLevel={headingLevel}
			description={
				creation === "instance-admin-only"
					? `${NOT_A_MEMBER} Ask an instance admin to create a workspace or add you.`
					: NOT_A_MEMBER
			}
			action={
				creation === "allowed" ? (
					<Link to="/workspaces/new" className={buttonVariants()}>
						<PlusIcon className="mr-2 size-4" />
						Create workspace
					</Link>
				) : undefined
			}
		/>
	);
}
