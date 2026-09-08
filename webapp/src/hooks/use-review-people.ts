import { useQuery } from "@tanstack/react-query";

import { listWorkspaceContributorsOptions } from "@/api/@tanstack/react-query.gen";
import {
	MEMBER_PAGE_SIZE,
	type PersonOption,
	type ReviewPeople,
} from "@/components/admin/practice-reviews/ReviewPersonFacet";
import { firstNonBlank } from "@/lib/text";

/**
 * The people a review list can be filtered by, fetched once per workspace and shared by every facet
 * on the screen — TanStack Query deduplicates by key, so the Delivery page's Recipient facet and the
 * Observations page's Developer facet cost one request between them.
 *
 * <p>Lives here rather than in the facet so the facet takes people as a prop: it is the same control
 * whether its list came from the API, from a fixture, or from a page that already had one.
 */
export function useReviewPeople(workspaceSlug: string): ReviewPeople {
	const membersQuery = useQuery({
		...listWorkspaceContributorsOptions({
			path: { workspaceSlug },
			query: { size: MEMBER_PAGE_SIZE },
		}),
	});
	const options: PersonOption[] = (membersQuery.data ?? [])
		.filter((member): member is typeof member & { userId: number } => member.userId != null)
		.map((member) => ({
			userId: member.userId,
			label: firstNonBlank(member.userName, member.userLogin) ?? `#${member.userId}`,
			secondary: member.userName && member.userLogin ? member.userLogin : undefined,
		}));

	return {
		options,
		// A full page is the only signal the endpoint gives that there may be more; it returns a bare
		// array, so there is no total to compare against.
		capped: (membersQuery.data?.length ?? 0) >= MEMBER_PAGE_SIZE,
		isLoading: membersQuery.isLoading,
		isError: membersQuery.isError,
	};
}
