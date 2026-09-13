import { DATA_HANDLING_DEFS, type DataHandlingTier } from "./data-handling-defs";
import { StatusBadge, type StatusBadgeProps } from "./StatusBadge";

export interface DataHandlingBadgeProps extends Omit<StatusBadgeProps, "def"> {
	tier: DataHandlingTier;
}

export function DataHandlingBadge({ tier, ...props }: DataHandlingBadgeProps) {
	return <StatusBadge def={DATA_HANDLING_DEFS[tier]} {...props} />;
}
