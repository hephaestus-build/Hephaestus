import type { WorkspaceOnboardingLink } from "@/api/types.gen";

/**
 * The account links still standing between a member and finished setup. A required link the
 * workspace owner broke is left out: it never holds anyone up, and the page and the route decide
 * that in one place so the Continue button and the completion request cannot disagree.
 */
export function openRequiredLinks<
	T extends Pick<WorkspaceOnboardingLink, "required" | "available" | "linked">,
>(links: readonly T[]): T[] {
	return requiredLinks(links).filter((link) => !link.linked);
}

/** The links the workspace requires and can offer, linked or not: one setup step each. */
export function requiredLinks<T extends Pick<WorkspaceOnboardingLink, "required" | "available">>(
	links: readonly T[],
): T[] {
	return links.filter((link) => link.required && link.available);
}
