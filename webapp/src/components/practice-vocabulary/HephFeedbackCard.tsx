import { Fragment, type ReactNode } from "react";

import type { ReviewedWorkRef } from "@/api/types.gen";
import { HephIcon } from "@/components/brand/HephIcon";
import type { FeedbackTextSegment } from "@/components/common/feedback-text";
import { FeedbackText } from "@/components/common/FeedbackText";
import { FOCUS_RING, HIT_AREA_24 } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import { SectionLabel } from "@/components/common/SectionLabel";
import { Skeleton } from "@/components/ui/skeleton";
import {
	type ArtifactKindId,
	artifactKindIcon,
	artifactKindNoun,
	type WorkProvider,
} from "@/lib/artifact-kinds";
import { cn } from "@/lib/utils";

import { ASSESSMENT_DEFS } from "./assessment-defs";
import { PracticePill } from "./PracticePill";
import { StatusTooltip } from "./StatusTooltip";

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
	 * A piece of feedback the work resolved, rather than a practice that simply held; the row
	 * reads the same either way.
	 */
	resolved?: boolean;
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
	reviewedWork: ReviewedWorkGroup[];
	onOpenPractice?: (practiceSlug: string) => void;
	/**
	 * The blocks after "What is holding up well"; a caller lists one only when it has something to
	 * say.
	 */
	blocks?: FeedbackBlock[];
	isLoading?: boolean;
	className?: string;
}

/**
 * Heph's card: the avatar in its margin column, then the card with what is holding up well, the
 * caller's own blocks beneath it, and the reviewed work the latest run looked at in the footer.
 * The page's overview and a group's level both speak through it. A block with nothing to say is
 * left out rather than filled with a sentence saying so, and with nothing to say at all the card
 * is not drawn.
 */
export function HephFeedbackCard({
	holdingUp,
	holdingUpNote,
	reviewedWork,
	onOpenPractice,
	blocks = [],
	isLoading = false,
	className,
}: HephFeedbackCardProps) {
	const hasBody = holdingUp.length > 0 || blocks.length > 0;
	if (!isLoading && !hasBody && reviewedWork.length === 0) return null;
	return (
		<div className={cn("flex items-start gap-3", className)}>
			<div className="flex w-15 shrink-0 flex-col items-center gap-2 pt-1">
				<span className="block size-12 -rotate-3" aria-hidden>
					<HephIcon size={48} strokeWidth={1.9} />
				</span>
				<SectionLabel>Heph</SectionLabel>
			</div>
			<div className="min-w-0 flex-1 overflow-hidden rounded-xl border bg-card">
				{isLoading ? (
					<OverviewSkeleton />
				) : (
					<>
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
												{holdingUpNote && (
													<p className="text-xs text-muted-foreground">{holdingUpNote}</p>
												)}
											</>
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
					</>
				)}
			</div>
		</div>
	);
}

interface HeldPracticeProps {
	row: HeldPracticeRow;
	onOpenPractice?: (practiceSlug: string) => void;
}

function HeldPractice({ row, onOpenPractice }: HeldPracticeProps) {
	const HeldIcon = ASSESSMENT_DEFS.GOOD.icon;
	return (
		<li className="grid grid-cols-[auto_minmax(0,1fr)] items-baseline gap-x-2.5 py-1">
			<StatusTooltip
				def={ASSESSMENT_DEFS.GOOD}
				render={<button type="button" />}
				// The icon is 14 px, so the hit area is widened a step beyond the constant's.
				className={cn(
					HIT_AREA_24,
					"top-0.5 inline-flex cursor-help rounded-sm before:-inset-1.5",
					FOCUS_RING,
				)}
			>
				<HeldIcon className="size-3.5 text-success" aria-hidden />
				<span className="sr-only">{ASSESSMENT_DEFS.GOOD.label}: </span>
			</StatusTooltip>
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

interface ReviewedWorkCellProps {
	group: ReviewedWorkGroup;
}

function ReviewedWorkCell({ group }: ReviewedWorkCellProps) {
	const KindIcon = artifactKindIcon(group.kind);
	const n = group.items.length;
	const noun = artifactKindNoun(group.kind, n, group.provider);
	return (
		<li className="flex flex-wrap items-center gap-x-1.5 text-sm">
			<KindIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
			<span className="whitespace-nowrap">
				<span className="font-semibold">{n}</span> <span className="font-medium">{noun}</span>
			</span>
			<span className="text-muted-foreground">
				(
				{group.items.map((item, index) => (
					<Fragment key={item.label}>
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

/** Mirrors the card at rest: three held rows, one paragraph, the footer band. */
function OverviewSkeleton() {
	return (
		<>
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
			<div className="flex items-end justify-between gap-4 border-t bg-sidebar p-4">
				<div className="grid grid-cols-[max-content] gap-x-7 gap-y-1.5 sm:grid-cols-[repeat(2,max-content)]">
					<Skeleton className="h-5 w-52" />
					<Skeleton className="h-5 w-64" />
					<Skeleton className="h-5 w-36" />
					<Skeleton className="h-5 w-44" />
				</div>
				<Skeleton className="h-4 w-28" />
			</div>
		</>
	);
}
