import { Link } from "@tanstack/react-router";
import { MessageSquareTextIcon, ScanSearchIcon } from "lucide-react";
import { type ReactNode, useId } from "react";

import type { AgentJob, Practice, ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import type { KnownArtifactKind } from "@/lib/artifact-kinds";

import { FeedbackRow } from "./FeedbackResults";
import { ObservationRow } from "./ObservationResults";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRowList } from "./ReviewRow";

/**
 * How many of each a scoped section shows before it links to the full list. Exported because the
 * caller's query has to *request* this many and the skeleton has to draw this many; three separate
 * numbers would resize the section the moment the answer arrived.
 */
export const REVIEW_PREVIEW_SIZE = 5;

export type ReviewSectionState<T> =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "pending" }
	| { status: "ready"; items: T[]; total: number };

/**
 * Deliberately not "nothing was found": the review never got as far as looking, so reading its empty
 * result as a clean bill of health would be exactly backwards.
 */
const INSUFFICIENT_EVIDENCE_EXPLANATION =
	"The review stopped before it assessed anything, because the material it needed was missing, unreadable, out of date, or not something it was allowed to read. No practice was judged — this is not a review that looked and found nothing.";

export interface ReviewOutputScope {
	agentJobId?: string;
	/** An artifact of a kind this build does not know still renders; it just cannot scope a link. */
	artifactKind?: KnownArtifactKind;
	artifactId?: number;
}

export interface ReviewOutputSectionsProps {
	workspaceSlug: string;
	scope: ReviewOutputScope;
	feedback: ReviewSectionState<ReviewFeedback>;
	observations: ReviewSectionState<ReviewObservation>;
	/**
	 * The workspace's practices, which the observation rows' practice links show as a hover card. A
	 * row names its practice but carries none of its prose, so the list is the join the card needs;
	 * the screen fetches it once and every row reads the record it names out of it. Optional because
	 * nothing the card shows is load-bearing — a caller without the list still gets working links.
	 */
	practices?: Practice[];
	/**
	 * Distinguishes "looked and found nothing" from "declined to look". Omitted by aggregate views,
	 * which span several runs and so have no single outcome.
	 */
	outcome?: AgentJob["reviewOutcome"];
}

/**
 * Each section renders the very rows its full list renders, rather than a layout of its own: a
 * record that looks like a different kind of thing depending on which screen reached it is the cost
 * of the alternative.
 */
export function ReviewOutputSections({
	workspaceSlug,
	scope,
	feedback,
	observations,
	practices,
	outcome,
}: ReviewOutputSectionsProps) {
	return (
		<>
			<ObservationsSection
				workspaceSlug={workspaceSlug}
				scope={scope}
				state={observations}
				practices={practices}
				outcome={outcome}
			/>
			<FeedbackSection
				workspaceSlug={workspaceSlug}
				scope={scope}
				state={feedback}
				outcome={outcome}
			/>
		</>
	);
}

function FeedbackSection({
	workspaceSlug,
	scope,
	state,
	outcome,
}: {
	outcome?: AgentJob["reviewOutcome"];
	workspaceSlug: string;
	scope: ReviewOutputScope;
	state: ReviewSectionState<ReviewFeedback>;
}) {
	const items = state.status === "ready" ? state.items : [];
	const headingId = useId();
	let body: ReactNode;
	if (state.status === "loading") {
		body = <ReviewResultsSkeleton label="Loading feedback" rows={REVIEW_PREVIEW_SIZE} />;
	} else if (state.status === "error") {
		body = (
			<QueryErrorAlert error={state.error} title="Couldn't load feedback" onRetry={state.onRetry} />
		);
	} else if (state.status === "pending") {
		body = (
			<p className="text-sm text-muted-foreground">
				Feedback will appear when the review finishes.
			</p>
		);
	} else if (items.length === 0) {
		body = (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<MessageSquareTextIcon />
					</EmptyMedia>
					<EmptyTitle>
						{outcome === "INSUFFICIENT_EVIDENCE" ? "Nothing was assessed" : "No feedback"}
					</EmptyTitle>
					{outcome === "INSUFFICIENT_EVIDENCE" && (
						<EmptyDescription>{INSUFFICIENT_EVIDENCE_EXPLANATION}</EmptyDescription>
					)}
				</EmptyHeader>
			</Empty>
		);
	} else {
		body = (
			<ReviewRowList label="Feedback">
				{items.map((item) => (
					<FeedbackRow key={item.id} workspaceSlug={workspaceSlug} feedback={item} scope={scope} />
				))}
			</ReviewRowList>
		);
	}
	return (
		<section aria-labelledby={headingId} className="space-y-3">
			<SectionHeader
				id={headingId}
				title="Feedback"
				to="/w/$workspaceSlug/admin/practices/reviews/delivery"
				workspaceSlug={workspaceSlug}
				scope={scope}
				total={state.status === "ready" ? state.total : 0}
				shown={items.length}
			/>
			{body}
		</section>
	);
}

function ObservationsSection({
	workspaceSlug,
	scope,
	state,
	practices,
	outcome,
}: {
	outcome?: AgentJob["reviewOutcome"];
	workspaceSlug: string;
	scope: ReviewOutputScope;
	state: ReviewSectionState<ReviewObservation>;
	practices?: Practice[];
}) {
	const items = state.status === "ready" ? state.items : [];
	const headingId = useId();
	let body: ReactNode;
	if (state.status === "loading") {
		body = <ReviewResultsSkeleton label="Loading observations" rows={REVIEW_PREVIEW_SIZE} />;
	} else if (state.status === "error") {
		body = (
			<QueryErrorAlert
				error={state.error}
				title="Couldn't load observations"
				onRetry={state.onRetry}
			/>
		);
	} else if (state.status === "pending") {
		body = (
			<p className="text-sm text-muted-foreground">
				Observations will appear when the review finishes.
			</p>
		);
	} else if (items.length === 0) {
		body = (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ScanSearchIcon />
					</EmptyMedia>
					<EmptyTitle>
						{outcome === "INSUFFICIENT_EVIDENCE"
							? "Nothing was assessed"
							: "No observations were recorded"}
					</EmptyTitle>
					{outcome === "INSUFFICIENT_EVIDENCE" && (
						<EmptyDescription>{INSUFFICIENT_EVIDENCE_EXPLANATION}</EmptyDescription>
					)}
				</EmptyHeader>
			</Empty>
		);
	} else {
		body = (
			<ReviewRowList label="Observations">
				{items.map((observation) => (
					<ObservationRow
						key={observation.id}
						workspaceSlug={workspaceSlug}
						observation={observation}
						practice={practices?.find((practice) => practice.slug === observation.practiceSlug)}
						scope={scope}
					/>
				))}
			</ReviewRowList>
		);
	}
	return (
		<section aria-labelledby={headingId} className="space-y-3">
			<SectionHeader
				id={headingId}
				title="Observations"
				to="/w/$workspaceSlug/admin/practices/reviews/observations"
				workspaceSlug={workspaceSlug}
				scope={scope}
				total={state.status === "ready" ? state.total : 0}
				shown={items.length}
			/>
			{body}
		</section>
	);
}

interface SectionHeaderProps {
	id: string;
	title: string;
	to:
		| "/w/$workspaceSlug/admin/practices/reviews/delivery"
		| "/w/$workspaceSlug/admin/practices/reviews/observations";
	workspaceSlug: string;
	scope: ReviewOutputScope;
	total: number;
	shown: number;
}

function SectionHeader({ id, title, to, workspaceSlug, scope, total, shown }: SectionHeaderProps) {
	return (
		<div className="flex flex-wrap items-end justify-between gap-2">
			<h3 id={id} className="text-lg font-semibold">
				{title}
			</h3>
			{total > shown && (
				<Link
					className="text-sm font-medium underline underline-offset-4"
					to={to}
					params={{ workspaceSlug }}
					search={scope}
				>
					See all {total} {title.toLowerCase()}
				</Link>
			)}
		</div>
	);
}
