import { Link } from "@tanstack/react-router";
import { Inbox } from "lucide-react";

import { Button } from "@/components/ui/button";

export interface WorkspaceCatalogUpdatesLinkProps {
	workspaceSlug: string;
	/** How many updates wait. Absent while the inbox loads, so no count is guessed. */
	count?: number;
}

/** Practice setup's way to Catalog updates, named as that page is. */
export function WorkspaceCatalogUpdatesLink({
	workspaceSlug,
	count,
}: WorkspaceCatalogUpdatesLinkProps) {
	return (
		<Button
			variant="outline"
			nativeButton={false}
			render={<Link to="/w/$workspaceSlug/admin/practices/releases" params={{ workspaceSlug }} />}
		>
			<Inbox /> Catalog updates{count === undefined ? "" : ` (${count})`}
		</Button>
	);
}
