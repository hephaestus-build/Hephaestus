import type { AccountRef, ReviewPerson } from "@/api/types.gen";
import { firstNonBlank, hasText } from "@/lib/text";

/**
 * Formatters over values that are not enums. A label map, badge variant or icon for an enum value
 * does not belong here: it goes in that enum's defs module under `@/components/practice-vocabulary`,
 * so the filter dropdown, the row badge and the detail header all read one entry.
 */
export function developerLabel(developer: ReviewPerson | null): string {
	if (!developer) {
		return "Unavailable developer";
	}
	return firstNonBlank(developer.displayName, developer.providerLogin) ?? "Unnamed developer";
}

/**
 * A review's requester as the person the result tables show, so "Started by" draws the same face and
 * name as "Developer": the account's own name and picture, else those of its first provider login.
 */
export function requesterAsPerson(account: AccountRef): ReviewPerson {
	const identity = account.identities.find((candidate) => hasText(candidate.login));
	return {
		accountId: account.id,
		avatarUrl: firstNonBlank(account.avatarUrl, identity?.avatarUrl) ?? null,
		displayName: firstNonBlank(account.displayName, identity?.displayName) ?? null,
		providerLogin: identity?.login ?? null,
	};
}
