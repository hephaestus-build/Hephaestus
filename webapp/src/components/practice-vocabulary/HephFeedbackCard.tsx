import { ArrowDownIcon } from "lucide-react";
import { Fragment, type ReactNode } from "react";

import { cn } from "cn";
import type { ReviewedWorkRef } from "@/api/types.gen";
import { HEPH_LABEL, HephIcon } from "@/components/brand/HephIcon";
import { InlineLink } from "@/components/common/InlineLink";
import { SectionLabel } from "@/components/common/SectionLabel";
import type { StatusDef } from "@/components/common/status-def";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { type ArtifactKindId, artifactKindNoun, type WorkProvider } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

import { FEEDBACK_STATE_DEFS } from "./feedback-state-defs";
import type { FeedbackTextSegment } from "./feedback-text";
import { FeedbackText } from "./FeedbackText";
import { PRACTICE_STANDING_DEFS } from "./practice-group-standing-defs";
import { PracticePill } from "./PracticePill";
import { StatusIcon } from "./StatusTooltip";

/**
 * One practice the reader keeps showing: the pill names it, the statement says how, the note
 * says on what.
 */
export interface HeldPracticeRow {
	practiceSlug: string;
	practiceName: string;
	/**
	 * How the practice holds, in the catalog's words — `holdsAs` on the wire — or what resolved
	 * the feedback; absent for a practice the catalog has no sentence for, whose row is the pill
	 * and the note.
	 */
	statement?: string;
	/**
	 * The evidence phrase — `held across four pull requests`, or `after !421, !423 and !425 came
	 * back clean` with its work references as links. It sits inside the statement's own text flow,
	 * so a wrap never strands it alone on a line.
	 */
	note: FeedbackTextSegment[];
	/**
	 * A piece of feedback the work resolved, rather than a practice that simply held. The row is
	 * drawn the same either way; what changes is what its tick is called, so a statement reading
	 * "Resolved by the work" is not announced as a strength.
	 */
	resolved?: boolean;
}

/**
 * One practice asking for the developer's attention: the pill names it, the sentence says what
 * happened on which work, and the link at the row's end goes to the feedback card that says what
 * to do about it. The registry entry decides the glyph and what a pointer reads on it, exactly as
 * a held row's tick does.
 */
export interface AttentionPracticeRow {
	/** The card below the row lands on. */
	feedbackId: string;
	practiceSlug: string;
	practiceName: string;
	/** An `ATTENTION_DEFS` entry. */
	def: StatusDef;
	/** "Back to 0 of 3 clean after !425", with the work it names linked. */
	sentence: FeedbackTextSegment[];
}

/**
 * The reviewed work of one kind at one provider the latest run looked at, listed by name; the
 * provider decides the noun, so GitLab's merge requests are not counted as pull requests.
 */
export interface ReviewedWorkGroup {
	kind: ArtifactKindId;
	provider?: WorkProvider;
	items: ReviewedWorkRef[];
}

/** One labelled block of the Heph card, after "What is holding up well". */
export interface FeedbackBlock {
	label: string;
	content: ReactNode;
}

/**
 * The muted label, then whatever the block says. The card draws the hairline between blocks, so
 * a block never knows whether it is the first.
 */
function FeedbackBlockView({ label, content }: FeedbackBlock) {
	return (
		<div className="flex flex-col gap-1.5 py-3 first:pt-0 last:pb-0">
			<SectionLabel>{label}</SectionLabel>
			{content}
		</div>
	);
}

export interface HephFeedbackCardProps {
	/** Most useful first; the composer hands over at most three. */
	holdingUp: HeldPracticeRow[];
	/** "Another two practices held too." when the rows could not name every held practice. */
	holdingUpNote?: string;
	/**
	 * What needs the developer next, at most two rows; with none the block is left out rather than
	 * drawn empty. The composer decides the precedence and the limit.
	 */
	needsAttention?: AttentionPracticeRow[];
	/**
	 * Takes the reader to the feedback card a row is about. Without it the rows carry no link: a
	 * control nobody can answer is left out rather than disabled.
	 */
	onReadFeedback?: (feedbackId: string) => void;
	reviewedWork: ReviewedWorkGroup[];
	onOpenPractice?: (practiceSlug: string) => void;
	/**
	 * The blocks after "What is holding up well"; a caller lists one only when it has something to
	 * say.
	 */
	blocks?: FeedbackBlock[];
}

const NO_BLOCKS: FeedbackBlock[] = [];
const NO_ATTENTION: AttentionPracticeRow[] = [];

/**
 * Heph's card: the mark in its margin column, then the card with what is holding up well, what
 * needs the developer's attention, the caller's own blocks beneath those, and the reviewed work
 * the latest run looked at in the footer. The hairline between the blocks is what keeps the good
 * news and the correction apart instead of interleaving them. A block with nothing to say is left
 * out rather than filled with a sentence saying so, and with nothing to say at all the card is not
 * drawn.
 */
export function HephFeedbackCard({
	holdingUp,
	holdingUpNote,
	needsAttention = NO_ATTENTION,
	onReadFeedback,
	reviewedWork,
	onOpenPractice,
	blocks = NO_BLOCKS,
}: HephFeedbackCardProps) {
	const hasBody = holdingUp.length > 0 || needsAttention.length > 0 || blocks.length > 0;
	if (!hasBody && reviewedWork.length === 0) {
		return null;
	}
	return (
		<HephCardFrame>
			{hasBody && (
				<div className="flex flex-col divide-y p-4">
					{holdingUp.length > 0 && (
						<FeedbackBlockView
							label="What is holding up well"
							content={
								<>
									<ul className="pt-0.5">
										{holdingUp.map((row) => (
											<HeldPractice
												key={row.practiceSlug}
												row={row}
												onOpenPractice={onOpenPractice}
											/>
										))}
									</ul>
									{hasText(holdingUpNote) && (
										<p className="text-xs text-muted-foreground">{holdingUpNote}</p>
									)}
								</>
							}
						/>
					)}
					{needsAttention.length > 0 && (
						<FeedbackBlockView
							label="What needs your attention"
							content={
								<ul className="pt-0.5">
									{needsAttention.map((row) => (
										<AttentionPractice
											key={row.feedbackId}
											row={row}
											onOpenPractice={onOpenPractice}
											onReadFeedback={onReadFeedback}
										/>
									))}
								</ul>
							}
						/>
					)}
					{blocks.map((block) => (
						<FeedbackBlockView key={block.label} {...block} />
					))}
				</div>
			)}
			{reviewedWork.length > 0 && (
				<div className={cn("bg-sidebar p-4", hasBody && "border-t")}>
					{/* Below `sm` the one column may shrink, so a long list of work wraps instead of
					    widening the page. */}
					<ul className="grid grid-cols-[minmax(0,max-content)] gap-x-7 gap-y-1.5 sm:grid-cols-[repeat(2,max-content)]">
						{reviewedWork.map((group) => (
							<ReviewedWorkCell key={`${group.kind} ${group.provider}`} group={group} />
						))}
					</ul>
				</div>
			)}
		</HephCardFrame>
	);
}

/**
 * The card while what it says loads: the mark stays, and the frame mirrors the card at rest —
 * three held rows, one paragraph, the footer band — so the page does not jump when it lands.
 */
export function HephFeedbackCardSkeleton() {
	return (
		<HephCardFrame>
			<div className="flex flex-col divide-y p-4">
				<div className="flex flex-col gap-1.5 pb-3">
					<Skeleton className="h-5 w-40" />
					<ul className="pt-0.5">
						{Array.from({ length: 3 }, (_, index) => (
							<li key={index} className="flex items-center gap-2.5 py-1">
								<Skeleton className="size-3.5 shrink-0 rounded-full" />
								<Skeleton className="h-5 w-44 rounded-full" />
								<Skeleton className="h-5 w-56" />
							</li>
						))}
					</ul>
				</div>
				<div className="flex flex-col gap-1.5 pt-3">
					<Skeleton className="h-5 w-24" />
					<Skeleton className="h-5 w-full max-w-2xl" />
					<Skeleton className="h-5 w-4/5" />
					<Skeleton className="mt-1 h-5 w-32" />
				</div>
			</div>
			<div className="border-t bg-sidebar p-4">
				<div className="grid grid-cols-[minmax(0,max-content)] gap-x-7 gap-y-1.5 sm:grid-cols-[repeat(2,max-content)]">
					<Skeleton className="h-5 w-52" />
					<Skeleton className="h-5 w-64" />
					<Skeleton className="h-5 w-36" />
					<Skeleton className="h-5 w-44" />
				</div>
			</div>
		</HephCardFrame>
	);
}

/** Heph's mark in its margin column beside the card's frame, shared by the card and its skeleton. */
function HephCardFrame({ children }: { children: ReactNode }) {
	return (
		<div className="flex items-start gap-3">
			<div className="flex shrink-0 pt-1">
				{/* The mark carries the name itself, so nothing is written under it; the tooltip says
				    the same words for a pointer. */}
				<Tooltip>
					<TooltipTrigger render={<span className="block size-12 -rotate-3" />}>
						<HephIcon size={48} strokeWidth={1.9} label={HEPH_LABEL} />
					</TooltipTrigger>
					<TooltipContent>{HEPH_LABEL}</TooltipContent>
				</Tooltip>
			</div>
			<div className="min-w-0 flex-1 overflow-hidden rounded-xl border bg-card">{children}</div>
		</div>
	);
}

interface HeldPracticeProps {
	row: HeldPracticeRow;
	onOpenPractice?: (practiceSlug: string) => void;
}

function HeldPractice({ row, onOpenPractice }: HeldPracticeProps) {
	const def =
		row.resolved === true ? FEEDBACK_STATE_DEFS.resolved : PRACTICE_STANDING_DEFS.STRENGTH;
	return (
		<li className="grid grid-cols-[auto_minmax(0,1fr)] items-baseline gap-x-2.5 py-1">
			<StatusIcon def={def} className="top-0.5" />
			<div className="flex min-w-0 flex-wrap items-baseline gap-2">
				<PracticePill
					name={row.practiceName}
					onOpen={onOpenPractice && (() => onOpenPractice(row.practiceSlug))}
				/>
				<span className="min-w-0 text-sm">
					{row.statement !== undefined && `${row.statement} `}
					<FeedbackText
						segments={row.note}
						onOpenPractice={onOpenPractice}
						className="text-xs text-muted-foreground"
					/>
				</span>
			</div>
		</li>
	);
}

interface AttentionPracticeProps {
	row: AttentionPracticeRow;
	onOpenPractice?: (practiceSlug: string) => void;
	onReadFeedback?: (feedbackId: string) => void;
}

/**
 * A row of "What needs your attention": the registry's glyph, the pill, the sentence, then the
 * link that takes the reader to the card below. The link's accessible name starts with its visible
 * words and names the practice, so a list of them read one after another does not read as one link
 * repeated; it is an `aria-label` for the reason `PracticeTable`'s `RowLinkCell` gives.
 */
function AttentionPractice({ row, onOpenPractice, onReadFeedback }: AttentionPracticeProps) {
	return (
		// Below `sm` the link takes its own line under the sentence instead of squeezing it: a phone
		// is too narrow for a pill, a sentence and a link on one row.
		<li className="grid grid-cols-[auto_minmax(0,1fr)] items-baseline gap-x-2.5 gap-y-1 py-1 sm:grid-cols-[auto_minmax(0,1fr)_auto]">
			<StatusIcon def={row.def} className="top-0.5" />
			<div className="flex min-w-0 flex-wrap items-baseline gap-2">
				<PracticePill
					name={row.practiceName}
					onOpen={onOpenPractice && (() => onOpenPractice(row.practiceSlug))}
				/>
				<FeedbackText
					segments={row.sentence}
					onOpenPractice={onOpenPractice}
					className="min-w-0 text-sm"
				/>
			</div>
			{onReadFeedback && (
				<InlineLink
					onClick={() => onReadFeedback(row.feedbackId)}
					aria-label={`Read the feedback for ${row.practiceName}`}
					className="col-start-2 inline-flex items-center gap-1 text-sm whitespace-nowrap sm:col-start-3 sm:pl-3"
				>
					Read the feedback
					<ArrowDownIcon className="size-3.5 shrink-0" aria-hidden />
				</InlineLink>
			)}
		</li>
	);
}

interface ReviewedWorkCellProps {
	group: ReviewedWorkGroup;
}

function ReviewedWorkCell({ group }: ReviewedWorkCellProps) {
	const WorkIcon = reviewedWorkIcon(group.kind, group.provider);
	const n = group.items.length;
	const noun = artifactKindNoun(group.kind, n, group.provider);
	return (
		<li className="flex flex-wrap items-center gap-x-1.5 text-sm">
			<WorkIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
			<span className="whitespace-nowrap">
				<span className="font-semibold">{n}</span> <span className="font-medium">{noun}</span>
			</span>
			<span className="text-muted-foreground">
				(
				{group.items.map((item, index) => (
					<Fragment key={`${item.kind} ${item.id}`}>
						{index > 0 && ", "}
						<InlineLink href={item.url} external>
							{item.label}
						</InlineLink>
					</Fragment>
				))}
				)
			</span>
		</li>
	);
}
