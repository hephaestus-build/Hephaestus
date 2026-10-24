import { StatusBadge } from "@/components/common/StatusBadge";

import { isEarlierBasis, STANDING_BASIS_DEFS, type StandingBasis } from "./standing-basis-defs";

export interface StandingBasisNoteProps {
	/** What the standing beside it was read from; nothing shows for current reviews or no verdict. */
	basis?: StandingBasis | null;
}

/**
 * Beside a standing that rests only on earlier reviews, says so in its own words. The words are
 * the whole message: no hover or tap opens more, so pointer, keyboard and touch read the same, and
 * on a narrow screen they wrap rather than being cut.
 */
export function StandingBasisNote({ basis }: StandingBasisNoteProps) {
	if (!isEarlierBasis(basis)) {
		return null;
	}
	return <StatusBadge wrap def={STANDING_BASIS_DEFS[basis]} />;
}
