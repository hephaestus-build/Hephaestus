import type { ReactNode } from "react";

import { HephIcon } from "@/components/brand/HephIcon";

/**
 * Heph speaking from a bubble. `intro` is fixed for the page; `narration` is the page's narration
 * line, announced as it changes, so every other line that follows the reader's answers is plain text.
 */
export function HephSays({ intro, narration }: { intro: ReactNode; narration: string }) {
	return (
		<div className="flex items-start gap-3">
			<HephIcon className="shrink-0" size={64} pad={2} />
			{/* The tail is opaque so it covers the bubble's own border; a tinted fill would let
			    that edge show straight through it. */}
			<div className="relative min-w-0 flex-1 rounded-xl border border-mentor/30 bg-card p-3 before:absolute before:top-6 before:-left-1.5 before:size-3 before:rotate-45 before:border-b before:border-l before:border-mentor/30 before:bg-card before:content-[''] sm:p-4">
				<p className="sr-only">Heph says:</p>
				<p className="text-sm leading-relaxed">{intro}</p>
				<p aria-live="polite" className="mt-2 text-sm font-medium">
					{narration}
				</p>
			</div>
		</div>
	);
}
