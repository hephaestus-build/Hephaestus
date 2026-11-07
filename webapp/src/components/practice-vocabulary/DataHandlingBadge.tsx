import { definitionFor } from "@/components/common/status-def";
import { StatusBadge, type StatusBadgeProps } from "@/components/common/StatusBadge";
import {
	UNKNOWN_DATA_HANDLING_DEF,
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
} from "./data-handling-defs";

export interface DataHandlingBadgeProps extends Omit<StatusBadgeProps, "def"> {
	tier: DataHandlingTier;
}

export function DataHandlingBadge({ tier, ...props }: DataHandlingBadgeProps) {
	return (
		<StatusBadge
			def={definitionFor(DATA_HANDLING_DEFS, tier, UNKNOWN_DATA_HANDLING_DEF)}
			{...props}
		/>
	);
}
