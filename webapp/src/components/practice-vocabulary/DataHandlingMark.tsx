import { cn } from "cn";
import { definitionFor } from "@/components/common/status-def";

import {
	UNKNOWN_DATA_HANDLING_DEF,
	DATA_HANDLING_DEFS,
	type DataHandlingTier,
} from "./data-handling-defs";

export interface DataHandlingMarkProps {
	tier: DataHandlingTier;
	/**
	 * `sr-only` where the icon stands beside a model's name, as in a cell or a run card. `visible`
	 * where the tier is the subject, such as the members a need misses.
	 */
	label?: "visible" | "sr-only";
	className?: string;
}

export function DataHandlingMark({ tier, label = "sr-only", className }: DataHandlingMarkProps) {
	const { icon: Icon, label: name } = definitionFor(
		DATA_HANDLING_DEFS,
		tier,
		UNKNOWN_DATA_HANDLING_DEF,
	);
	return (
		<span className={cn("inline-flex min-w-0 items-center gap-1 text-muted-foreground", className)}>
			<Icon className="size-3.5 shrink-0" aria-hidden />
			<span className={label === "sr-only" ? "sr-only" : "min-w-0 truncate"}>{name}</span>
		</span>
	);
}
