import { useQuery } from "@tanstack/react-query";

import { listThreadsOptions } from "@/api/@tanstack/react-query.gen";
import { statusOf } from "@/session/api-client";
import { useWorkspace } from "@/workspace/workspace-context";

/**
 * Whether Heph can talk to this person here. Two things must both be true: the workspace turned the
 * mentor on, and the account holds mentor access, which an instance administrator grants. The second
 * surfaces as a 403 on the thread list; it is explained, never silently worked around.
 */
export function useHephAccess() {
	const workspace = useWorkspace();
	const threads = useQuery({
		...listThreadsOptions({ path: { workspaceSlug: workspace.workspaceSlug } }),
		enabled: workspace.mentorEnabled,
	});
	return { access: accessOf(workspace.mentorEnabled, threads), threads };
}

export type HephAccess = "workspace-off" | "no-access" | "checking" | "unreachable" | "available";

function accessOf(
	mentorEnabled: boolean,
	threads: { isError: boolean; isPending: boolean; error: unknown },
): HephAccess {
	if (!mentorEnabled) {
		return "workspace-off";
	}
	if (threads.isError) {
		return statusOf(threads.error) === 403 ? "no-access" : "unreachable";
	}
	return threads.isPending ? "checking" : "available";
}

export const ACCESS_EXPLANATION = {
	"workspace-off": {
		title: "Heph is off in this workspace",
		message: "A workspace administrator can turn the mentor on in the workspace settings.",
	},
	"no-access": {
		title: "Heph is not switched on for you yet",
		message:
			"Talking to Heph needs mentor access on your account. Ask an administrator of this Hephaestus to grant it, then check again here.",
	},
} as const;
