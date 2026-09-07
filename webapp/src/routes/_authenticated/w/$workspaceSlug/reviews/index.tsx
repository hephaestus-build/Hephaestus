import { useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";

import { listTracedArtifactsOptions } from "@/api/@tanstack/react-query.gen";
import { type TraceSearch, traceSearchSchema } from "@/components/practice-trace/trace-search";
import { TRACE_PAGE_SIZE, TraceListPage } from "@/components/practice-trace/TraceListPage";
import { pageParam } from "@/lib/search-params";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/reviews/")({
	validateSearch: traceSearchSchema,
	component: ReviewActivityListRoute,
});

function ReviewActivityListRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const updateSearch = (patch: Partial<TraceSearch>) =>
		void navigate({
			search: (previous) => {
				const next = { ...previous, ...patch };
				return { ...next, page: pageParam(next.page) };
			},
			replace: true,
		});

	const query = useQuery({
		...listTracedArtifactsOptions({
			path: { workspaceSlug },
			query: { page: search.page ?? 0, size: TRACE_PAGE_SIZE, artifactKind: search.kind },
		}),
	});

	return (
		<TraceListPage
			workspaceSlug={workspaceSlug}
			search={search}
			onSearchChange={updateSearch}
			artifacts={query.data}
			isLoading={query.isLoading}
			error={query.isError ? query.error : undefined}
			onRetry={() => void query.refetch()}
		/>
	);
}
