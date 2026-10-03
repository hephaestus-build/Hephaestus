import { Link } from "@tanstack/react-router";
import { Folders, PlusIcon } from "lucide-react";

import { buttonVariants } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";

/** Level 1 where this is the whole page; 2 where it sits under something else, such as the sidebar. */
export function NoWorkspace({ headingLevel = 1 }: { headingLevel?: 1 | 2 }) {
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia variant="icon">
					<Folders />
				</EmptyMedia>
				<EmptyTitle role="heading" aria-level={headingLevel}>
					No workspace
				</EmptyTitle>
				<EmptyDescription>You&apos;re not a member of any workspace yet.</EmptyDescription>
			</EmptyHeader>
			<Link to="/workspaces/new" className={buttonVariants()}>
				<PlusIcon className="mr-2 size-4" />
				Create Workspace
			</Link>
		</Empty>
	);
}
