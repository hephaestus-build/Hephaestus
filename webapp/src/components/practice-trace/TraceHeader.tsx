import { ExternalLinkIcon } from "lucide-react";

import type { ArtifactTrace } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { ARTIFACT_KIND, artifactKindIcon, artifactKindLabel } from "@/lib/artifact-kinds";

/**
 * The kinds the request endpoint accepts. A conversation thread and a document are reviewed on the
 * occasion their source produces, and asking for one by hand is refused. Kept here because nothing
 * on the wire says which kinds have a front door; being wrong in this direction costs a missing
 * button rather than a broken one.
 */
const REVIEWABLE_ON_DEMAND: readonly string[] = [ARTIFACT_KIND.pullRequest, ARTIFACT_KIND.issue];

export interface TraceHeaderProps {
	trace: ArtifactTrace;
	onRequestReview: () => void;
	requestPending: boolean;
}

/** What the work is, where it lives, and the one thing a reader can do about it from here. */
export function TraceHeader({ trace, onRequestReview, requestPending }: TraceHeaderProps) {
	const KindIcon = artifactKindIcon(trace.artifactKind);

	return (
		<div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
			<div className="min-w-0 space-y-3">
				<p className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
					<KindIcon className="size-4 shrink-0" aria-hidden />
					{artifactKindLabel(trace.artifactKind)}
				</p>
				<h1 className="text-2xl font-semibold tracking-tight break-words">
					{trace.title}
					{trace.number != null && (
						<span className="ml-2 font-normal text-muted-foreground tabular-nums">
							#{trace.number}
						</span>
					)}
				</h1>
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
					{trace.container && <span className="break-all">{trace.container}</span>}
					{trace.url && (
						<a
							href={trace.url}
							target="_blank"
							rel="noopener noreferrer"
							className="inline-flex items-center gap-1 font-medium text-foreground hover:underline"
						>
							Open the original
							<ExternalLinkIcon className="size-3.5 shrink-0" aria-hidden />
							<span className="sr-only"> (opens in a new tab)</span>
						</a>
					)}
				</div>
			</div>
			{REVIEWABLE_ON_DEMAND.includes(trace.artifactKind) && (
				<Button
					variant="outline"
					className="shrink-0 sm:self-start"
					disabled={requestPending}
					onClick={onRequestReview}
				>
					{requestPending ? "Asking…" : "Review this now"}
				</Button>
			)}
		</div>
	);
}
