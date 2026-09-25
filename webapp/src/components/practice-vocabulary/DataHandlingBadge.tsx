import { StatusBadge, type StatusBadgeProps } from "@/components/common/StatusBadge";
import { DATA_HANDLING_DEFS, type DataHandlingTier } from "./data-handling-defs";

export interface DataHandlingBadgeProps extends Omit<StatusBadgeProps, "def"> {
	tier: DataHandlingTier;
}

export function DataHandlingBadge({ tier, ...props }: DataHandlingBadgeProps) {
	return <StatusBadge def={DATA_HANDLING_DEFS[tier]} {...props} />;
}
