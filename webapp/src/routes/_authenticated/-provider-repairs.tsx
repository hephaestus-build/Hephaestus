import { ProviderResourceRepairs } from "@/components/admin/integrations/ProviderResourceRepairs";
import { useConnectionProviderResourceRepairs } from "@/hooks/use-provider-resource-repairs";

/** The key remounts the dialog, page and mutation state when the workspace or connection changes. */
export function WorkspaceProviderRepairs({
	workspaceSlug,
	connectionId,
}: {
	workspaceSlug: string;
	connectionId: number;
}) {
	const repairs = useConnectionProviderResourceRepairs({ workspaceSlug, connectionId });
	return <ProviderResourceRepairs {...repairs} />;
}
