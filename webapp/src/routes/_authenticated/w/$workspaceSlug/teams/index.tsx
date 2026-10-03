import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { getAllTeamsOptions } from "@/api/@tanstack/react-query.gen";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { TeamsPage } from "@/components/teams/TeamsPage";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { pageHead } from "@/lib/page-title";
import { hasText } from "@/lib/text";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/teams/")({
	head: pageHead("Teams"),
	component: TeamsContainer,
});

function TeamsContainer() {
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const teamsQuery = useQuery({
		...getAllTeamsOptions({ path: { workspaceSlug: workspaceSlug ?? "" } }),
		enabled: Boolean(workspaceSlug),
	});

	if (!hasText(workspaceSlug)) {
		return <NoWorkspace headingLevel={1} />;
	}

	return (
		<TeamsPage teams={teamsQuery.data ?? []} isLoading={teamsQuery.isLoading || !workspaceSlug} />
	);
}
