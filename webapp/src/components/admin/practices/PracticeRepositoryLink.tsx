import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

/** The card's `id` on Practice setup, which links from a practice's panel scroll to. */
export const PRACTICE_REPOSITORY_SECTION_ID = "practice-repository";

/** A link to the practice repository card on Practice setup. It closes any open level. */
export function PracticeRepositoryLink({
	workspaceSlug,
	children,
}: {
	workspaceSlug: string;
	children: ReactNode;
}) {
	return (
		<Link
			to="/w/$workspaceSlug/admin/practices"
			params={{ workspaceSlug }}
			search={{}}
			hash={PRACTICE_REPOSITORY_SECTION_ID}
			className="font-medium underline underline-offset-4 hover:text-foreground"
		>
			{children}
		</Link>
	);
}
