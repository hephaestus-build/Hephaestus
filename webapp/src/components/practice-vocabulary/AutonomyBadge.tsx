import type { PracticeAutonomy } from "@/lib/practice-autonomy";

import { StatusBadge, type StatusBadgeProps } from "@/components/common/StatusBadge";
import { AUTONOMY_DEFS } from "./autonomy-defs";

export interface AutonomyBadgeProps extends Omit<StatusBadgeProps, "def"> {
	autonomy: PracticeAutonomy;
}

export function AutonomyBadge({ autonomy, ...props }: AutonomyBadgeProps) {
	return <StatusBadge def={AUTONOMY_DEFS[autonomy]} {...props} />;
}
