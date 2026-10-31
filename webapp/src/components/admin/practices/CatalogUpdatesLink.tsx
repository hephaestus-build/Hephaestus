import { Link } from "@tanstack/react-router";
import { Inbox } from "lucide-react";

import { Button } from "@/components/ui/button";

export interface CatalogUpdatesLinkProps {
	workspaceSlug: string;
	/** How many updates wait. Absent until the inbox has loaded, so no count is guessed. */
	count?: number;
}

/** Practice setup's way to Catalog updates, named as that page is. */
export function CatalogUpdatesLink({ workspaceSlug, count }: CatalogUpdatesLinkProps) {
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
