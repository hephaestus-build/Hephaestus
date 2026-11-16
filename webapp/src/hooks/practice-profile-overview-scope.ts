import { getOwnPracticeProfileOverviewQueryKey } from "@/api/@tanstack/react-query.gen";

/**
 * Every workspace's overview of the reader's own practices, for a change that touches them all, such
 * as the reader's AI choice. A module of its own, so a page that only invalidates the overview does not
 * load what reads it.
 */
export function practiceProfileOverviewQueryScope() {
	const [{ path: _path, ...scope }] = getOwnPracticeProfileOverviewQueryKey({
		path: { workspaceSlug: "" },
	});
	return [scope];
}
