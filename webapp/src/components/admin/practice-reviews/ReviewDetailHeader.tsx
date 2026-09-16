import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

import { RelativeTime } from "@/components/common/RelativeTime";

export interface ReviewDetailHeaderProps {
	/** Above the title, because they say what kind of thing the reader is about to read. */
	chips?: ReactNode;
	title: ReactNode;
	provenance?: ReactNode;
	/** Controls that act on the whole record. */
	actions?: ReactNode;
}

export function ReviewDetailHeader({ chips, title, provenance, actions }: ReviewDetailHeaderProps) {
	return (
		<header className="space-y-3">
			<div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
				<div className="min-w-0 space-y-2">
					{chips && <div className="flex flex-wrap items-center gap-2">{chips}</div>}
					<h2 className="text-2xl font-semibold tracking-tight break-words">{title}</h2>
					{provenance}
				</div>
				{actions}
			</div>
		</header>
	);
}

export interface ReviewProvenanceLineProps {
	workspaceSlug: string;
	agentJobId: string;
	/** How this record came about: "Observed", "Composed". A verb, so the line reads as a sentence. */
	verb: string;
	at: Date;
}

/**
 * Answers "which review made this?" with a link and not the job's UUID: the id is in the address bar
 * of the page the link goes to, and was never something to read.
 */
export function ReviewProvenanceLine({
	workspaceSlug,
	agentJobId,
	verb,
	at,
}: ReviewProvenanceLineProps) {
	return (
		<p className="text-sm text-muted-foreground">
			{verb}{" "}
			<Link
				to="/w/$workspaceSlug/admin/practices/reviews/$jobId"
				params={{ workspaceSlug, jobId: agentJobId }}
				className="font-medium text-foreground underline underline-offset-4"
			>
				in a review
			</Link>{" "}
			<RelativeTime value={at} />
		</p>
	);
}

/** A `<dl>` rather than prose, because these are labelled values and a screen reader should announce
 * them as pairs. */
export function ReviewFactGrid({ children }: { children: ReactNode }) {
	return (
		<dl className="grid gap-4 rounded-lg border p-4 sm:grid-cols-2 lg:grid-cols-3">{children}</dl>
	);
}

export function ReviewFact({ label, children }: { label: string; children: ReactNode }) {
	return (
		<div className="min-w-0 space-y-1">
			<dt className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{label}</dt>
			<dd className="min-w-0 text-sm">{children}</dd>
		</div>
	);
}
