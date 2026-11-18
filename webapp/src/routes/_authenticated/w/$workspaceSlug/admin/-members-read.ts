import { listMembershipsOptions } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceMembersTableView } from "@/components/admin/members/WorkspaceMembersTable";
import { hasText } from "@/lib/text";

// The route's loader and component share these reads, so they live outside the route file (`prefetchPageRead`).

/** The address's search, as the route validates it. */
export interface MembersSearch {
	q?: string;
	team?: string;
	sort?: "name" | "username";
	desc?: boolean;
	page?: number;
	size?: number;
}

/** The table's view from the address: a value the address leaves out is its default. */
export function viewOf(search: MembersSearch): WorkspaceMembersTableView {
	return {
		q: search.q ?? "",
		team: search.team ?? "all",
		sort: search.sort ?? "name",
		desc: search.desc ?? false,
		page: search.page ?? 0,
		size: search.size ?? 10,
	};
}

/** The page's member read, shared by its loader and its component so both hit one cache entry. */
export function membersRead(workspaceSlug: string, view: WorkspaceMembersTableView) {
	return listMembershipsOptions({
		path: { workspaceSlug },
		query: {
			q: hasText(view.q.trim()) ? view.q.trim() : undefined,
			team: view.team === "all" ? undefined : view.team,
			page: view.page,
			size: view.size,
			sort: [`${view.sort},${view.desc ? "desc" : "asc"}`],
		},
	});
}
