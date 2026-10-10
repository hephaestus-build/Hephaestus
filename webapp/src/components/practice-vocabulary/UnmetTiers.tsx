import { cn } from "cn";

import {
	DATA_HANDLING_DEFS,
	DATA_HANDLING_TIERS,
	type DataHandlingTier,
	notSetForPhrase,
} from "./data-handling-defs";

export interface UnmetTiersProps {
	/** The member tiers that no model serves. */
	tiers: readonly DataHandlingTier[];
	className?: string;
}

/**
 * The members a needed model misses, with their tiers' icons, on AI models and on a practice's
 * panel alike: one phrase for one fact. A model that misses no one draws nothing.
 */
export function UnmetTiers({ tiers, className }: UnmetTiersProps) {
	if (tiers.length === 0) {
		return null;
	}
	return (
		<span className={cn("flex items-start gap-1.5 text-xs text-muted-foreground", className)}>
			<span className="flex shrink-0 gap-1 pt-px">
				{DATA_HANDLING_TIERS.filter((tier) => tiers.includes(tier)).map((tier) => {
					const Icon = DATA_HANDLING_DEFS[tier].icon;
					return <Icon key={tier} className="size-3.5" aria-hidden />;
				})}
			</span>
			<span>{notSetForPhrase(tiers)}</span>
		</span>
	);
}
