import type { WorkspaceOnboarding } from "@/api/types.gen";

export function workspaceOnboarding(workspaceName = "Acme"): WorkspaceOnboarding {
	return {
		workspaceName,
		enabled: false,
		needsWelcome: false,
		aiChoiceRequired: false,
		completed: false,
		revision: 0,
		links: [],
		aiOptions: [],
	};
}
