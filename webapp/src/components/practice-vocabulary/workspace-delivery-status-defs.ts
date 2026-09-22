import { CircleCheckIcon, CirclePauseIcon } from "lucide-react";
import type { PracticeReviewSettings } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

export type WorkspaceDeliveryStatus = PracticeReviewSettings["deliveryStatus"];

export const WORKSPACE_DELIVERY_STATUS_DEFS: StatusDefs<WorkspaceDeliveryStatus> = {
	ACTIVE: {
		label: "Active",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "Finished feedback may be sent when every other delivery check allows it.",
	},
	PAUSED: {
		label: "Paused",
		icon: CirclePauseIcon,
		badgeVariant: "warning",
		description: "No external practice feedback may leave this workspace.",
	},
};
