import { useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";

import { getAllTeamsOptions } from "@/api/@tanstack/react-query.gen";
import { queryLoadState } from "@/components/common/panel-state";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { TeamsPage, type TeamsPageProps } from "@/components/teams/TeamsPage";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { pageHead } from "@/lib/page-title";
import { hasText } from "@/lib/text";
import { NoWorkspaceContainer } from "@/routes/-NoWorkspaceContainer";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/teams/")({
	head: pageHead("Teams"),
	component: TeamsContainer,
});

function TeamsContainer() {
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const queryClient = useQueryClient();
	const options = getAllTeamsOptions({ path: { workspaceSlug: workspaceSlug ?? "" } });
	const teamsQuery = useQuery({
		...options,
		enabled: Boolean(workspaceSlug),
	});

	useEffect(() => {
		if (
			teamsQuery.isError &&
			teamsQuery.data !== undefined &&
			!queryErrorIsRetryable(teamsQuery.error)
		) {
			// A later connection failure must not restore teams from before an access refusal.
			void queryClient.resetQueries({ queryKey: options.queryKey, exact: true });
		}
	}, [queryClient, options.queryKey, teamsQuery.isError, teamsQuery.error, teamsQuery.data]);

	if (!hasText(workspaceSlug)) {
		return <NoWorkspaceContainer />;
	}

	// A refresh that failed and may pass on retry keeps the teams on screen; anything else is the read's state.
	const state: TeamsPageProps["state"] =
		teamsQuery.isError && teamsQuery.data !== undefined && queryErrorIsRetryable(teamsQuery.error)
			? {
					status: "ready",
					refresh: {
						error: teamsQuery.error,
						pending: teamsQuery.isFetching,
						onRetry: () => {
							void teamsQuery.refetch();
						},
					},
				}
			: queryLoadState(teamsQuery);

	return <TeamsPage teams={teamsQuery.data ?? []} state={state} />;
}
