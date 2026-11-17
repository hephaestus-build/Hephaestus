import { Popover as PopoverPrimitive } from "@base-ui/react/popover";
import { ArrowRightIcon, ListChecksIcon, XIcon } from "lucide-react";
import { type ReactElement, type ReactNode, useId, useRef, useState } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import { definitionFor } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	GET_STARTED_STATUS_DEFS,
	type GetStartedStatus,
	TOUR_CHAPTER_FINISHED,
} from "@/components/practice-vocabulary/get-started-status-defs";
import { Button } from "@/components/ui/button";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import {
	Popover,
	PopoverContent,
	PopoverDescription,
	PopoverTitle,
	PopoverTrigger,
} from "@/components/ui/popover";
import { Skeleton } from "@/components/ui/skeleton";

import type { GetStartedOpening } from "./get-started-record";

/** One step to first value. Its status is a fact from the server, so it changes by itself. */
export interface GetStartedSetupRow {
	id: string;
	title: string;
	status: GetStartedStatus;
	/** What stands now, in the server's words. */
	summary: ReactNode;
	/** The facts under the summary, such as what starts a review. */
	notes?: ReactNode;
	/** The one way on: a link to the page that changes it, or a button. Absent once there is nothing to do. */
	action?: ReactElement;
}

/** A way to learn a part of Hephaestus. It never holds the list open. */
export interface GetStartedLearnRow {
	id: string;
	title: string;
	summary: string;
	/** The control the title is: a link to a tour chapter or to a page. */
	render: ReactElement;
	/** This account finished it in this browser, the only place that knows it. */
	finished: boolean;
}

/** The list's facts, not yet, or a read that failed. */
export type GetStartedState =
	| { status: "loading" }
	| { status: "error"; onRetry: () => void }
	| { status: "ready"; setup: readonly GetStartedSetupRow[]; learn: readonly GetStartedLearnRow[] };

export interface GetStartedProps {
	state: GetStartedState;
	open: boolean;
	/**
	 * `dismissed` is true when the reader closed the card on purpose: Close, Escape, or the pill.
	 * A press on the page or Tab out of the card closes it too, but says nothing about what they read.
	 */
	onOpenChange: (open: boolean, dismissed: boolean) => void;
	/**
	 * How the card opened. The reader's press moves focus to the first step's way on. An open by
	 * itself, as on a first visit, leaves focus where the reader is and says so politely.
	 */
	opening: GetStartedOpening;
	/** Takes the list out of the corner. The account menu shows it again. */
	onHide: () => void;
}

/** How many setup steps are done, once the list knows and has any. */
function progressOf(state: GetStartedState): { done: number; total: number } | undefined {
	if (state.status !== "ready" || state.setup.length === 0) {
		return undefined;
	}
	return {
		done: state.setup.filter((row) => row.status === "done").length,
		total: state.setup.length,
	};
}

/** Every setup step is done: the list has nothing left to hold it open. */
export function getStartedDone(state: GetStartedState): boolean {
	return state.status === "ready" && state.setup.every((row) => row.status === "done");
}

/**
 * The steps between a new member and their first practice review, then ways to learn the page, in a
 * card that the pill in the bottom-right corner opens. The card is non-modal: it covers no page, it
 * traps no focus, and a press outside or Escape closes it. The pill stays until every setup step is
 * done and the reader closes the card, or until the reader hides it.
 */
export function GetStarted({ state, open, onOpenChange, opening, onHide }: GetStartedProps) {
	const announcement = useAnnouncement(state, open, opening);
	const popup = useRef<HTMLDivElement>(null);
	const progress = progressOf(state);
	const count = progress === undefined ? undefined : `${progress.done} of ${progress.total}`;
	const done = getStartedDone(state);
	return (
		<Popover
			open={open}
			onOpenChange={(next, details) =>
				onOpenChange(next, details.reason !== "outside-press" && details.reason !== "focus-out")
			}
		>
			<div className="fixed right-4 bottom-4 z-40" data-get-started-corner="">
				<PopoverTrigger
					render={
						<Button
							variant="outline"
							size="lg"
							shape="pill"
							// 44px high, so the pill is an easy target in the corner of a phone.
							className="h-11 shadow-lg"
							// The visible words first, so a voice command that reads them finds the pill (WCAG 2.5.3).
							aria-label={count === undefined ? "Get started" : `Get started ${count} done`}
						/>
					}
				>
					<ListChecksIcon aria-hidden />
					{/* Below 400px the count and the icon carry the pill, and the name keeps both words. */}
					<span className="max-[400px]:sr-only">Get started</span>{" "}
					{count !== undefined && <span className="text-muted-foreground">{count}</span>}
				</PopoverTrigger>
				{/* Mounted before its message, so a screen reader hears it (ARIA22). */}
				<p role="status" className="sr-only">
					{announcement}
				</p>
			</div>
			<PopoverContent
				ref={popup}
				data-get-started-corner=""
				side="top"
				align="end"
				sideOffset={8}
				// A first-visit open leaves focus where it is; the reader's own press takes it to the
				// first step that is still theirs to take, or to the first control.
				initialFocus={opening === "self" ? false : () => firstOpenStep(popup.current) ?? true}
				// A fade, never a zoom or a slide: the card appears where the reader looks.
				motion="fade"
				// A tucked-away feedback draft waits above the pill. The card opens above that button, so the
				// button that brings the draft back stays reachable.
				className="max-h-[calc(100dvh-8rem)] w-[min(26rem,calc(100vw-2rem))] overflow-y-auto [body:has([data-feedback-draft])_&]:max-h-[calc(100dvh-11.5rem)] [body:has([data-feedback-draft])_&]:-translate-y-14"
			>
				<div className="flex items-start justify-between gap-2">
					<div className="flex min-w-0 flex-col gap-0.5">
						<PopoverTitle className="text-base">Get started</PopoverTitle>
						{count !== undefined && <PopoverDescription>{count} done</PopoverDescription>}
					</div>
					<PopoverPrimitive.Close
						render={<Button variant="ghost" size="icon-sm" aria-label="Close Get started" />}
					>
						<XIcon aria-hidden />
					</PopoverPrimitive.Close>
				</div>
				<CardBody state={state} />
				<div className="flex justify-end">
					{done ? (
						<PopoverPrimitive.Close render={<Button variant="outline" size="sm" />}>
							Close
						</PopoverPrimitive.Close>
					) : (
						<Button variant="quiet" size="sm" onClick={onHide}>
							Hide this list
						</Button>
					)}
				</div>
			</PopoverContent>
		</Popover>
	);
}

/** The way on of the first step that is not done yet: where a reader's own open puts focus. */
function firstOpenStep(popup: HTMLElement | null): HTMLElement | undefined {
	return (
		popup?.querySelector<HTMLElement>("[data-open-step] :is(a[href], button)") ??
		popup?.querySelector<HTMLElement>("[data-learn-step] :is(a[href], button)") ??
		undefined
	);
}

function CardBody({ state }: { state: GetStartedState }) {
	if (state.status === "loading") {
		return (
			<div className="flex flex-col gap-3" aria-hidden>
				<Skeleton className="h-4 w-24" />
				<Skeleton className="h-14 w-full" />
				<Skeleton className="h-14 w-full" />
				<Skeleton className="h-14 w-full" />
			</div>
		);
	}
	if (state.status === "error") {
		return (
			<div className="flex flex-col items-start gap-2">
				<p className="text-muted-foreground">
					Your next steps could not load. Your pages work as before.
				</p>
				<Button variant="outline" size="sm" onClick={state.onRetry}>
					Try again
				</Button>
			</div>
		);
	}
	const { setup, learn } = state;
	return (
		<>
			{getStartedDone(state) && (
				<p className="text-muted-foreground">
					{setup.length === 0 ? "Nothing to set up here." : "Every step is done."} When you close
					this list, it leaves the corner. Get started in your account menu shows it again.
				</p>
			)}
			{setup.length > 0 && (
				<RowGroup title="Set up">
					{setup.map((row) => (
						<SetupRow key={row.id} row={row} />
					))}
				</RowGroup>
			)}
			{learn.length > 0 && (
				<RowGroup title="Learn">
					{learn.map((row) => (
						<LearnRow key={row.id} row={row} />
					))}
				</RowGroup>
			)}
		</>
	);
}

function RowGroup({ title, children }: { title: string; children: ReactNode }) {
	const headingId = useId();
	return (
		<div className="flex flex-col gap-1.5">
			<h3 id={headingId} className="text-sm font-medium text-muted-foreground">
				{title}
			</h3>
			<ol aria-labelledby={headingId} className="rounded-xl border bg-card">
				{children}
			</ol>
		</div>
	);
}

function SetupRow({ row }: { row: GetStartedSetupRow }) {
	return (
		<Item
			render={<li />}
			variant="row"
			className="items-start"
			{...(row.status === "done" ? {} : { "data-open-step": "" })}
		>
			<ItemContent className="min-w-0 basis-48">
				<ItemTitle className="line-clamp-none">{row.title}</ItemTitle>
				<ItemDescription className="line-clamp-none">{row.summary}</ItemDescription>
				{row.notes !== undefined && (
					<div className="flex flex-col gap-2 text-sm text-muted-foreground">{row.notes}</div>
				)}
				{row.action !== undefined && <div className="pt-1">{row.action}</div>}
			</ItemContent>
			<ItemActions>
				<StatusBadge def={definitionFor(GET_STARTED_STATUS_DEFS, row.status)} />
			</ItemActions>
		</Item>
	);
}

function LearnRow({ row }: { row: GetStartedLearnRow }) {
	const summaryId = useId();
	const finishedId = useId();
	const FinishedIcon = TOUR_CHAPTER_FINISHED.icon;
	return (
		<Item render={<li />} variant="row" className="items-start" data-learn-step="">
			<ItemContent className="min-w-0 basis-48">
				<ItemTitle className="line-clamp-none">
					{/* The arrow says the title leads somewhere, as the steps' own links do. */}
					<InlineLink
						render={row.render}
						aria-describedby={row.finished ? `${summaryId} ${finishedId}` : summaryId}
						className="inline-flex items-center gap-1 font-medium"
					>
						{row.title}
						<ArrowRightIcon aria-hidden className="size-3.5 shrink-0" />
					</InlineLink>
				</ItemTitle>
				<ItemDescription id={summaryId} className="line-clamp-none">
					{row.summary}
				</ItemDescription>
			</ItemContent>
			{row.finished && (
				<ItemActions>
					<span
						id={finishedId}
						className="inline-flex items-center gap-1.5 text-sm text-muted-foreground"
					>
						<FinishedIcon aria-hidden className="size-4" />
						{TOUR_CHAPTER_FINISHED.label}
					</span>
				</ItemActions>
			)}
		</Item>
	);
}

/**
 * What the corner's status line says: that the card opened by itself, and when a setup row turns
 * done while the list is mounted, such as after the reader's first review lands. Nothing on the first
 * render or on a poll that changed nothing, so a screen reader hears only news. The rows last seen
 * are kept in state, and the route builds new rows on every render, so they are compared by their
 * statuses.
 */
function useAnnouncement(state: GetStartedState, open: boolean, opening: GetStartedOpening) {
	const setup = state.status === "ready" ? state.setup : undefined;
	const [seen, setSeen] = useState(setup);
	const [wasOpen, setWasOpen] = useState(false);
	const [message, setMessage] = useState("");
	const progress = progressOf(state);
	const count =
		progress === undefined ? "" : ` ${progress.done} of ${progress.total} steps are done.`;
	if (open !== wasOpen) {
		setWasOpen(open);
		// Cleared on close, so the next open by itself is news again, even with the same words.
		setMessage(open && opening === "self" ? `Get started opened in the corner.${count}` : "");
	}
	if (setup !== undefined && !sameStatuses(seen, setup)) {
		setSeen(setup);
		const before = new Map(seen?.map((row) => [row.id, row.status]));
		const finished = setup.filter(
			(row) => row.status === "done" && before.has(row.id) && before.get(row.id) !== "done",
		);
		if (finished.length > 0) {
			const done = setup.filter((row) => row.status === "done").length;
			const rest =
				done === setup.length ? "Every step is done." : `${done} of ${setup.length} done.`;
			setMessage(`Done: ${finished.map((row) => row.title).join(", ")}. ${rest}`);
		}
	}
	return message;
}

function sameStatuses(
	before: readonly GetStartedSetupRow[] | undefined,
	now: readonly GetStartedSetupRow[],
): boolean {
	return (
		before !== undefined &&
		before.length === now.length &&
		before.every((row, at) => {
			const current = now.at(at);
			return current !== undefined && row.id === current.id && row.status === current.status;
		})
	);
}
