import { StatusBadge } from "@/components/common/StatusBadge";
import { TRACE_OUTCOME_DEFS } from "@/components/practice-vocabulary/trace-outcome-defs";

import type { TraceOutcome } from "./trace-format";

export interface TraceOutcomeBadgeProps {
	outcome: TraceOutcome;
	className?: string;
}

export function TraceOutcomeBadge({ outcome, className }: TraceOutcomeBadgeProps) {
	return <StatusBadge def={TRACE_OUTCOME_DEFS[outcome]} className={className} />;
}
