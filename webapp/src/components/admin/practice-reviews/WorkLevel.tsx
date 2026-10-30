import type { ReactNode } from "react";

import type { ItemEvent, ItemFact, ItemRef, ReviewRef } from "@/api/types.gen";
import { DotSeparator } from "@/components/common/DotSeparator";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { SectionStack } from "@/components/layout/SectionStack";
import { RefusalFixLink } from "@/components/practice-trace/RefusalFixLink";
import {
	ITEM_EVENT_FOUND_BY,
	ITEM_EVENT_REASON_SENTENCES,
	ITEM_EVENT_STATE_DEFS,
} from "@/components/practice-vocabulary/item-event-defs";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import { itemKindLabel, itemName } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

import { levelFoldKey } from "./level-folds";
import { LevelBodySkeleton } from "./LevelBodySkeleton";
import {
	type SheetEvent,
	SheetHistory,
	SheetSection,
	SheetStatusLine,
	SheetTechnical,
} from "./RecordSheet";
import { RequestReviewAction } from "./RequestReviewAction";
import { itemLevel, reviewLevel } from "./review-levels";
import { ReviewFact, ReviewFactGrid } from "./ReviewFactGrid";
import { ReviewItemLink } from "./ReviewItem";
import { ReviewLevelHeader } from "./ReviewLevelHeader";

const NO_REVIEWS: readonly ReviewRef[] = [];

export interface WorkLevelProps {
	workspaceSlug: string;
	nested?: boolean;
	path: LevelPath;
	item: PanelState<{ item: ItemRef }>;
	events?: PanelState<{ events: ItemEvent[] }>;
	reviews?: readonly ReviewRef[];
	eventLabel: (name: string) => string;
	facts?: PanelState<{ facts: ItemFact[] }>;
	onRequestReview?: () => void;
	requesting?: boolean;
	requestBlocked?: string;
	/**
	 * The answer to the last ask, drawn by the route: why it started nothing, or the way to the review
	 * it created.
	 */
	requestOutcome?: ReactNode;
}

export function WorkLevel({
	workspaceSlug,
	nested,
	path,
	item: state,
	events: eventsState,
	reviews = NO_REVIEWS,
	eventLabel,
	facts: factsState,
	onRequestReview,
	requesting = false,
	requestBlocked,
	requestOutcome,
}: WorkLevelProps) {
	const item = state.status === "ready" ? state.item : undefined;
	const events = eventsState?.status === "ready" ? eventsState.events : [];
	const latest = latestEvent(events);
	const latestReview = reviews[0];
	const factsFailed = factsState?.status === "error";
	const eventsFailed = eventsState?.status === "error";
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="item"
			loading={state.status === "loading"}
			chips={
				<div className="flex flex-col gap-2">
					{state.status === "ready" && latestReview !== undefined && (
						<SheetStatusLine
							status={<StatusBadge def={REVIEW_STATUS_DEFS[latestReview.status]} />}
							flag={<StatusBadge def={REVIEW_ORIGIN_DEFS[latestReview.origin]} />}
							figure={
								<InlineLink
									render={<DetailStackLink entry={reviewLevel(latestReview.id)} single />}
								>
									Open review
								</InlineLink>
							}
							at={{
								label: latestReview.completedAt === null ? "review created" : "review completed",
								date: latestReview.completedAt ?? latestReview.createdAt,
							}}
						/>
					)}
					{latest && (
						<SheetStatusLine
							status={<StatusBadge def={ITEM_EVENT_STATE_DEFS[latest.state]} />}
							figure={eventsFigure(events)}
							at={{ label: "latest event", date: latest.occurredAt }}
						/>
					)}
				</div>
			}
			title={item && (item.title ?? item.label)}
			description={item && <ReviewItemLink item={item} />}
		/>
	);
	if (state.status !== "ready" || item === undefined) {
		return (
			<>
				{header}
				<DrawerBody className="flex flex-col gap-8 pt-2">
					{state.status === "error" ? (
						<>
							{/* A read that fails after an ask still leaves its answer, and the way to its review. */}
							{requestOutcome}
							<QueryErrorAlert
								error={state.error}
								title="We could not load this work"
								onRetry={state.onRetry}
							/>
						</>
					) : (
						<LevelBodySkeleton />
					)}
				</DrawerBody>
			</>
		);
	}
	const history: SheetEvent[] = [
		...reviews.map((review) => reviewEvent(review)),
		...events.map((event) => itemEvent(event, eventLabel, workspaceSlug)),
		...(item.removedAt === null
			? []
			: [{ key: "removed", at: item.removedAt, title: "Removed from the provider" }]),
	];
	return (
		<>
			{header}
			<DrawerBody className="flex flex-col gap-8 pt-2">
				{requestOutcome}
				<SectionStack>
					<SheetSection
						part="what"
						foldKey={factsFailed ? undefined : levelFoldKey("work", "what")}
					>
						<ReviewFactGrid>
							<ReviewFact label="Kind">{itemKindLabel(item.kind, 1, item.provider)}</ReviewFact>
							{hasText(item.container) && !item.label.includes(item.container) && (
								<ReviewFact label="Where">{item.container}</ReviewFact>
							)}
							{hasText(item.path) && (
								<ReviewFact label="Path">
									<code className="break-all">{item.path}</code>
								</ReviewFact>
							)}
							{hasText(item.parentId) && (
								<ReviewFact label="Part of">
									<InlineLink render={<DetailStackLink entry={itemLevel(item.parentId)} single />}>
										Open the work it belongs to
									</InlineLink>
								</ReviewFact>
							)}
							{factsState?.status === "ready" &&
								factsState.facts.map((fact) => (
									<ReviewFact key={fact.name} label={fact.name}>
										<FactValue fact={fact} />
									</ReviewFact>
								))}
						</ReviewFactGrid>
						{factsFailed && (
							<QueryErrorAlert
								error={factsState.error}
								title="We could not load what the provider reports about this work"
								onRetry={factsState.onRetry}
							/>
						)}
					</SheetSection>
					<SheetSection
						part="state"
						foldKey={eventsFailed ? undefined : levelFoldKey("work", "state")}
						description="Reviews of this work and what became of its recorded events."
					>
						{eventsState?.status === "loading" && <LevelBodySkeleton />}
						{eventsFailed && (
							<QueryErrorAlert
								error={eventsState.error}
								title="We could not load the events on this work"
								onRetry={eventsState.onRetry}
							/>
						)}
						{(eventsState === undefined || eventsState.status === "ready") &&
							(history.length === 0 ? (
								<p className="text-sm text-muted-foreground">No event was recorded on this work.</p>
							) : (
								<SheetHistory events={history} />
							))}
					</SheetSection>
					<SheetTechnical
						foldKey={levelFoldKey("work", "technical")}
						facts={[
							{ label: "Work ID", value: <code>{item.id}</code>, copy: item.id },
							{ label: "Kind", value: <code>{item.kind}</code> },
							{
								label: "Latest version",
								value: hasText(latest?.itemVersion) ? <code>{latest.itemVersion}</code> : "—",
								copy: latest?.itemVersion ?? undefined,
							},
						]}
					/>
				</SectionStack>
			</DrawerBody>
			{onRequestReview !== undefined && (
				<DrawerFooter>
					<RequestReviewAction
						label="Request review"
						request={{
							work: itemName(item),
							onRequest: onRequestReview,
							requesting,
							blocked: requestBlocked,
						}}
					/>
				</DrawerFooter>
			)}
		</>
	);
}

function latestEvent(events: readonly ItemEvent[]): ItemEvent | undefined {
	let latest: ItemEvent | undefined;
	for (const event of events) {
		if (latest === undefined || event.occurredAt > latest.occurredAt) {
			latest = event;
		}
	}
	return latest;
}

function eventsFigure(events: readonly ItemEvent[]): string {
	const count = events.length === 1 ? "1 event" : `${events.length} events`;
	// The same test as the state badge, so the header cannot contradict itself. A migrated event can
	// have started a review that it no longer links to.
	const started = events.filter((event) => event.state === "STARTED").length;
	return `${count}, ${started === 0 ? "none" : String(started)} started a review`;
}

function FactValue({ fact }: { fact: ItemFact }) {
	const value = hasText(fact.value) ? fact.value : "None";
	return hasText(fact.url) ? (
		<InlineLink href={fact.url} external className="break-all">
			{value}
		</InlineLink>
	) : (
		<span className="break-words">{value}</span>
	);
}

function itemEvent(
	event: ItemEvent,
	eventLabel: (name: string) => string,
	workspaceSlug: string,
): SheetEvent {
	const foundBy = ITEM_EVENT_FOUND_BY[event.foundBy];
	return {
		key: event.id,
		at: event.occurredAt,
		title: eventLabel(event.name),
		detail: (
			<>
				<span className="block">
					<span title={foundBy.description}>{foundBy.label}</span>
					<DotSeparator inline />
					{ITEM_EVENT_STATE_DEFS[event.state].label}
				</span>
				{event.reason !== null && (
					<span className="block">
						<span>{ITEM_EVENT_REASON_SENTENCES[event.reason]} </span>
						<RefusalFixLink workspaceSlug={workspaceSlug} reason={event.reason} />
					</span>
				)}
			</>
		),
	};
}

function reviewEvent(review: ReviewRef): SheetEvent {
	return {
		key: `review:${review.id}`,
		at: review.createdAt,
		title: (
			<InlineLink render={<DetailStackLink entry={reviewLevel(review.id)} single />}>
				{REVIEW_ORIGIN_DEFS[review.origin].label} review
			</InlineLink>
		),
		detail: (
			<>
				Current status: <StatusBadge def={REVIEW_STATUS_DEFS[review.status]} />
			</>
		),
	};
}
