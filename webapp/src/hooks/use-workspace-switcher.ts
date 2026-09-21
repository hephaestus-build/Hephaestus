import { useNavigate, useParams } from "@tanstack/react-router";
import { toast } from "sonner";

export function useWorkspaceSwitcher() {
	const navigate = useNavigate();
	const params = useParams({ strict: false });
	const currentWorkspaceSlug = params.workspaceSlug;
	// A route is portable only when `workspaceSlug` is the only thing in the URL that names a
	// workspace-scoped row: any second path param names a row the new workspace does not have.
	const portable =
		currentWorkspaceSlug !== undefined &&
		Object.keys(params).every((parameter) => parameter === "workspaceSlug");

	// The destination route's search middleware runs after this updater, so clearing the search leaves
	// each route to declare which of its own options are portable through `retainSearchParams`.
	return async (workspace: { displayName: string; workspaceSlug: string }) => {
		const { displayName, workspaceSlug } = workspace;
		if (workspaceSlug === currentWorkspaceSlug) {
			return;
		}

		if (portable) {
			await navigate({
				to: ".",
				params: { workspaceSlug },
				search: () => ({}),
				replace: true,
			});
			return;
		}

		await navigate({
			to: "/w/$workspaceSlug",
			params: { workspaceSlug },
			search: () => ({}),
			replace: true,
		});
		if (currentWorkspaceSlug !== undefined) {
			toast.info(`Switched to ${displayName}`, {
				description:
					"This page is specific to the previous workspace, so Hephaestus opened the new workspace's home page.",
			});
		}
	};
}
