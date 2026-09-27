import { useQuery } from "@tanstack/react-query";
import { Stack, useNavigation, useRouter } from "expo-router";

import { closeSheet } from "@/account/close-sheet";
import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { currentSessionKey } from "@/session/authority";
import { useSession } from "@/session/session-store";
import { QueryStates } from "@/ui/QueryStates";
import { Screen } from "@/ui/Screen";
import { selectWorkspace, useSelectedWorkspace } from "@/workspace/workspace-store";
import { WorkspaceList } from "@/workspace/WorkspaceList";

/**
 * Choosing the workspace the tabs show. Choosing one closes the account: the tabs open afresh in it,
 * with nothing — a list, a conversation, a draft — carried over from the one before.
 */
export default function Workspaces() {
	const navigation = useNavigation();
	const router = useRouter();
	const session = useSession();
	const selected = useSelectedWorkspace();
	const workspaces = useQuery(listWorkspacesOptions({}));
	const apiBaseUrl = session.status === "signedIn" ? session.instance.apiBaseUrl : "";
	return (
		<>
			<Stack.Screen options={{ title: "Workspace" }} />
			<Screen
				onRefresh={() => {
					void workspaces.refetch();
				}}
				refreshing={workspaces.isRefetching}
			>
				<QueryStates
					query={workspaces}
					loadingLabel="Loading your workspaces"
					errorTitle="Could not load your workspaces"
				>
					{(list) => (
						<WorkspaceList
							workspaces={list}
							selectedSlug={selected}
							onSelect={(workspace) => {
								if (workspace.workspaceSlug !== selected) {
									void selectWorkspace(currentSessionKey(), apiBaseUrl, workspace.workspaceSlug);
								}
								// The account sheet closes as a whole, back to the tabs.
								closeSheet(navigation.getParent(), () => router.replace("/"));
							}}
						/>
					)}
				</QueryStates>
			</Screen>
		</>
	);
}
