import type { WorkspaceOnboarding } from "@/api/types.gen";

export function workspaceOnboarding(workspaceName = "Acme"): WorkspaceOnboarding {
	return {
		workspaceName,
		enabled: false,
		needsSetup: false,
		aiChoiceRequired: false,
		links: [],
		aiOptions: [],
	};
}
