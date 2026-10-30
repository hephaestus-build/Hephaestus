import { CircleDashedIcon } from "lucide-react";

import type { WorkspaceMembership } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

/** Only the exception has a status: a member who answered draws nothing, and never says which way. */
export type MemberPublicActivity = "NOT_ANSWERED";

/**
 * Whether a member still has to answer this workspace's public activity question, as a workspace
 * admin reads it on Members. Their account-wide choice is not part of it, and neither is which way
 * anyone chose.
 */
export const MEMBER_PUBLIC_ACTIVITY_DEFS: StatusDefs<MemberPublicActivity> = {
	NOT_ANSWERED: {
		label: "Not answered yet",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "They have not answered this workspace’s question.",
	},
};

export function memberPublicActivity(
	membership: Pick<WorkspaceMembership, "publicActivityAnswered">,
): MemberPublicActivity | null {
	return membership.publicActivityAnswered === false ? "NOT_ANSWERED" : null;
}
