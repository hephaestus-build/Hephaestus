import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { getAllTeamsOptions } from "@/api/@tanstack/react-query.gen";
import { TeamsPage } from "@/components/teams/TeamsPage";
import { NoWorkspace } from "@/components/workspace/NoWorkspace";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { hasText } from "@/lib/text";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/teams/")({
	component: TeamsContainer,
});

function TeamsContainer() {
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const teamsQuery = useQuery({
		...getAllTeamsOptions({ path: { workspaceSlug: workspaceSlug ?? "" } }),
		enabled: Boolean(workspaceSlug),
	});

	if (!hasText(workspaceSlug)) {
		return <NoWorkspace />;
	}

	return (
		<TeamsPage teams={teamsQuery.data ?? []} isLoading={teamsQuery.isLoading || !workspaceSlug} />
	);
}
