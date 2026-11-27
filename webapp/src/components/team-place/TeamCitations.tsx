import type { ReactElement } from "react";

import { InlineLink } from "@/components/common/InlineLink";

import type { TeamAnchor, TeamCitation } from "./team-place-entries";

export interface TeamCitationsProps {
	citations: readonly TeamCitation[];
	/**
	 * The link that opens one anchor in its reviewed version, such as a router `Link`. Without it each place is named
	 * but not linked.
	 */
	anchorLink?: (anchor: TeamAnchor) => ReactElement;
}

/**
 * The places in the work that Hephaestus's message cites. Each opens only in its own reviewed version. A place whose
 * source is gone says "No longer available", never its old label, and opens nothing.
 */
export function TeamCitations({ citations, anchorLink }: TeamCitationsProps) {
	if (citations.length === 0) {
		return null;
	}
	return (
		<section aria-label="Cited in the work" className="flex flex-col gap-1 text-sm">
			<p className="font-medium text-foreground">Cited in the work</p>
			<ul className="flex flex-col gap-1">
				{citations.map(({ observationId, anchor, available }, index) => (
					// A message can cite one observation at several places, and a gone place has no anchor to key on.
					<li key={`${observationId}:${index}`}>
						{available && anchor !== undefined ? (
							<InlineLink render={anchorLink?.(anchor)}>
								<bdi>{anchor.label}</bdi> in version {anchor.versionSequence}
							</InlineLink>
						) : (
							<span className="text-muted-foreground">No longer available</span>
						)}
					</li>
				))}
			</ul>
		</section>
	);
}
