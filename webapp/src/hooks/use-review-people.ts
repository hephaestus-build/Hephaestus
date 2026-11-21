import { useQuery } from "@tanstack/react-query";

import {
	getPerPersonResultsOptions,
	listMembershipsOptions,
} from "@/api/@tanstack/react-query.gen";
import {
	MEMBER_PAGE_SIZE,
	type PersonOption,
	type ReviewPeople,
} from "@/components/admin/practice-reviews/ReviewPersonFacet";
import { hasText } from "@/lib/text";

export function useReviewPeople(workspaceSlug: string): ReviewPeople {
	const membersQuery = useQuery({
		...listMembershipsOptions({ path: { workspaceSlug }, query: { size: MEMBER_PAGE_SIZE } }),
	});
	// Unknown counts as off: a filter the server refuses is worse than one that appears a moment late.
	const perPersonQuery = useQuery(getPerPersonResultsOptions({ path: { workspaceSlug } }));
	const options: PersonOption[] = (membersQuery.data?.content ?? []).map(({ account }) => {
		const login = account.identities.find((identity) => hasText(identity.login))?.login;
		return {
			accountId: account.id,
			label: hasText(account.displayName) ? account.displayName : (login ?? `#${account.id}`),
			secondary: hasText(account.displayName) && hasText(login) ? login : undefined,
		};
	});

	return {
		options,
		capped: (membersQuery.data?.totalElements ?? 0) > options.length,
		isLoading: membersQuery.isLoading,
		isError: membersQuery.isError,
		named: perPersonQuery.data?.enabled === true,
	};
}
