import { NoWorkspace, type NoWorkspaceProps } from "@/components/common/NoWorkspace";
import { useWorkspaceCreation } from "@/hooks/use-workspace-creation";

/** "No workspace" with the step the creation policy lets this person take. */
export function NoWorkspaceContainer(props: Omit<NoWorkspaceProps, "creation">) {
	const creation = useWorkspaceCreation();
	return <NoWorkspace {...props} creation={creation} />;
}
