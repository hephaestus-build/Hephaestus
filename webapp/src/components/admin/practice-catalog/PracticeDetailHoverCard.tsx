import type { ReactElement } from "react";

import type { Practice } from "@/api/types.gen";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";
import { artifactKindLabel } from "@/lib/artifact-kinds";

export interface PracticeDetailHoverCardProps {
	practice: Practice;
	/** The practice's own link, which stays the trigger rather than gaining a second tab stop. */
	children: ReactElement;
}

/**
 * The prose a practice carries, on the name that already links to it.
 *
 * Nothing in here may be load-bearing for the decision the surrounding row is making: touch has
 * neither hover nor a focus ring, so the card never opens there. That is also why this wraps the
 * link rather than a help icon — the fallback has to be where the same tap already goes.
 *
 * Keyboard readers do get it: Base UI's preview card opens on focus-visible as well as hover, unlike
 * Radix's, whose docs warn it must not carry essential content for exactly that reason.
 */
export function PracticeDetailHoverCard({ practice, children }: PracticeDetailHoverCardProps) {
	const why = practice.whyItMatters?.trim();
	const good = practice.whatGoodLooksLike?.trim();
	if (!why && !good) {
		return children;
	}

	return (
		<HoverCard>
			<HoverCardTrigger render={children} />
			{/* `align="start"`: centred on a name that runs most of the row's width, the card would
			    start somewhere the eye did not leave. */}
			<HoverCardContent align="start" className="w-80 space-y-2">
				<p className="font-medium">{practice.name}</p>
				{why && <p className="text-muted-foreground">{why}</p>}
				{good && (
					<p className="text-muted-foreground">
						<span className="text-foreground">What good looks like: </span>
						{good}
					</p>
				)}
				<p className="text-xs text-muted-foreground">
					Reviewed on: {artifactKindLabel(practice.artifactKind)}
				</p>
			</HoverCardContent>
		</HoverCard>
	);
}
