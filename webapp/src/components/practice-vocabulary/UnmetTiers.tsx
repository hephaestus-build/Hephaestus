import { cn } from "cn";
import { definitionFor } from "@/components/common/status-def";

import {
	UNKNOWN_DATA_HANDLING_DEF,
	DATA_HANDLING_DEFS,
	DATA_HANDLING_TIERS,
	type DataHandlingTier,
	notSetForPhrase,
} from "./data-handling-defs";

export interface UnmetTiersProps {
	tiers: readonly DataHandlingTier[];
	className?: string;
}

export function UnmetTiers({ tiers, className }: UnmetTiersProps) {
	if (tiers.length === 0) {
		return null;
	}
	return (
		<span className={cn("flex items-start gap-1.5 text-xs text-muted-foreground", className)}>
			<span className="flex shrink-0 gap-1 pt-px">
				{DATA_HANDLING_TIERS.filter((tier) => tiers.includes(tier)).map((tier) => {
					const Icon = definitionFor(DATA_HANDLING_DEFS, tier, UNKNOWN_DATA_HANDLING_DEF).icon;
					return <Icon key={tier} className="size-3.5" aria-hidden />;
				})}
			</span>
			<span>{notSetForPhrase(tiers)}</span>
		</span>
	);
}
