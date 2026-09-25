import { getMemberOnboardingQueryKey } from "@/api/@tanstack/react-query.gen";

/**
 * The setup cache across every workspace. The AI choice is one answer per account, so saving it
 * anywhere has to refresh what every workspace's setup page shows.
 */
export function memberOnboardingQueryScope() {
	const [{ path: _path, ...scope }] = getMemberOnboardingQueryKey({
		path: { workspaceSlug: "" },
	});
	return [scope];
}
