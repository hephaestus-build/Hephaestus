import type { TracedSignal } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

import { RefusalFixLink } from "./RefusalFixLink";
import {
	DISCOVERED_VIA_DESCRIPTIONS,
	DISCOVERED_VIA_LABELS,
	occurrenceDomId,
	SIGNAL_STATE_LABELS,
	SIGNAL_STATE_REASON_LABELS,
} from "./trace-format";

export interface TraceSignalTimelineProps {
	signals: TracedSignal[];
	workspaceSlug: string;
	canAdminister: boolean;
}

/** Everything recorded about one piece of work, oldest first, and what each occurrence led to. */
export function TraceSignalTimeline({
	signals,
	workspaceSlug,
	canAdminister,
}: TraceSignalTimelineProps) {
	return (
		<section aria-labelledby="trace-signals-heading" className="min-w-0 space-y-3">
			<div className="space-y-1">
				<h2 id="trace-signals-heading" className="text-lg font-semibold">
					What we noticed
				</h2>
				<p className="text-sm text-muted-foreground">
					Everything recorded about this work, oldest first.
				</p>
			</div>
			{signals.length === 0 ? (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyTitle>Nothing was recorded about this work</EmptyTitle>
						<EmptyDescription>
							Without an occurrence to react to, no practice was ever asked a question about it.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			) : (
				<ol className="min-w-0 space-y-0 border-l pl-4">
					{signals.map((signal) => (
						// `tabIndex={-1}` is what makes the jump land: following the fragment moves focus
						// here, so a keyboard or screen-reader user arrives at the occurrence itself.
						<li
							key={signal.id}
							id={occurrenceDomId(signal.id)}
							tabIndex={-1}
							className="relative min-w-0 scroll-mt-24 rounded-md py-2.5 outline-none target:bg-muted focus:bg-muted"
						>
							<span
								className="absolute top-4 -left-[1.3125rem] size-2 rounded-full bg-border ring-4 ring-background"
								aria-hidden
							/>
							<p className="text-sm font-medium break-words">{signal.displayName}</p>
							<p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
								<RelativeTime value={signal.occurredAt} className="text-xs" />
								<span aria-hidden>·</span>
								<Tooltip>
									<TooltipTrigger className="cursor-help underline decoration-dotted underline-offset-4">
										{DISCOVERED_VIA_LABELS[signal.discoveredVia]}
									</TooltipTrigger>
									<TooltipContent>
										{DISCOVERED_VIA_DESCRIPTIONS[signal.discoveredVia]}
									</TooltipContent>
								</Tooltip>
								<span aria-hidden>·</span>
								<span>{SIGNAL_STATE_LABELS[signal.state]}</span>
							</p>
							{signal.stateReason && (
								<p className="mt-1 flex flex-wrap items-baseline gap-x-1.5 text-xs break-words text-muted-foreground">
									<span>{SIGNAL_STATE_REASON_LABELS[signal.stateReason]}.</span>
									<RefusalFixLink
										workspaceSlug={workspaceSlug}
										reason={signal.stateReason}
										canAdminister={canAdminister}
										className="font-medium text-foreground underline underline-offset-4 hover:no-underline"
									/>
								</p>
							)}
						</li>
					))}
				</ol>
			)}
		</section>
	);
}
