import type { ActivityCoverage } from "@/api/types.gen";
import { formatDate } from "@/lib/dates";

/**
 * How far back the counts are complete: "History since 3 March 2024 for 12 of 14 repositories", or,
 * while the complete repositories hold no history yet, how many repositories are still incomplete.
 */
export function coverageNote({
	since,
	completeRepositories,
	totalRepositories,
}: ActivityCoverage): string | undefined {
	const incomplete = totalRepositories - completeRepositories;
	if (totalRepositories === 0 || (since === undefined && incomplete === 0)) {
		return undefined;
	}
	const repositories = totalRepositories === 1 ? "repository" : "repositories";
	if (since === undefined) {
		const which =
			completeRepositories === 0
				? `the ${totalRepositories} ${repositories}`
				: `${incomplete} of ${totalRepositories} ${repositories}`;
		return `The history of ${which} is not complete yet, so the counts can be low.`;
	}
	return `History since ${formatDate(since)} for ${completeRepositories} of ${totalRepositories} ${repositories}.`;
}
