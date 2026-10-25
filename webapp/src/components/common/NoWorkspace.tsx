import { Link } from "@tanstack/react-router";
import { Folders, PlusIcon } from "lucide-react";

import { EmptyState } from "@/components/common/EmptyState";
import { buttonVariants } from "@/components/ui/button";

/** Level 1 where this is the whole page; 2 where it sits under something else, such as the sidebar. */
export function NoWorkspace({ headingLevel = 1 }: { headingLevel?: 1 | 2 }) {
	return (
		<EmptyState
			variant="plain"
			icon={<Folders />}
			title="No workspace"
			headingLevel={headingLevel}
			description="You are not a member of any workspace yet."
			action={
				<Link to="/workspaces/new" className={buttonVariants()}>
					<PlusIcon className="mr-2 size-4" />
					Create workspace
				</Link>
			}
		/>
	);
}
