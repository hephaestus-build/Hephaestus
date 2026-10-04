import { EyeOffIcon } from "lucide-react";

import type { StatusDef } from "@/components/common/status-def";

/** The mark on practice-page feedback a workspace admin withdrew because what it said was wrong. */
export const FEEDBACK_WITHDRAWN_DEF: StatusDef = {
	label: "Withdrawn",
	icon: EyeOffIcon,
	badgeVariant: "destructive",
	description:
		"A workspace admin withdrew this from the developer’s practice page. The observations behind it are unchanged.",
};
