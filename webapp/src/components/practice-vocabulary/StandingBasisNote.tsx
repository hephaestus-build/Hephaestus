import { InfoTip } from "@/components/common/InfoTip";
import { definitionFor } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";

import { isEarlierBasis, STANDING_BASIS_DEFS, type StandingBasis } from "./standing-basis-defs";

export interface StandingBasisNoteProps {
	/** What the standing beside it was read from; nothing shows for current reviews or no verdict. */
	basis?: StandingBasis | null;
}

/**
 * Beside a standing that rests only on earlier reviews, says so in its own words, which wrap on a
 * narrow screen rather than being cut. The (i) after them holds the registry's whole sentence: what
 * such a standing leaves out, and whether the original criteria are still known.
 */
export function StandingBasisNote({ basis }: StandingBasisNoteProps) {
	if (!isEarlierBasis(basis)) {
		return null;
	}
	const def = definitionFor(STANDING_BASIS_DEFS, basis);
	return (
		<span className="inline-flex max-w-full items-center gap-0.5">
			<StatusBadge wrap def={def} />
			<InfoTip label="About this standing" lead={def.description} />
		</span>
	);
}
