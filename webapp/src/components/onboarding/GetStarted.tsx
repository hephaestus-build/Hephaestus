import { ArrowRightIcon } from "lucide-react";
import { type ReactElement, type ReactNode, useId, useState } from "react";

import { InfoTip } from "@/components/common/InfoTip";
import { InlineLink } from "@/components/common/InlineLink";
import { definitionFor } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { Section } from "@/components/layout/Section";
import {
	GET_STARTED_STATUS_DEFS,
	type GetStartedStatus,
	TOUR_CHAPTER_FINISHED,
} from "@/components/practice-vocabulary/get-started-status-defs";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";

import { GET_STARTED_ID } from "./get-started-open";

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
	/** The control the title is: a link, or a button that starts a tour chapter. */
	render: ReactElement;
	/** This account finished it in this browser, the only place that knows it. */
	finished: boolean;
}

/**
 * The list's facts, or not yet. There is no error branch: a failed read shows nothing, because the
 * list is optional guidance, and an alert for it would greet every member, though most of them never
 * see the list.
 */
export type GetStartedState =
	| { status: "loading" }
	| { status: "ready"; setup: readonly GetStartedSetupRow[]; learn: readonly GetStartedLearnRow[] };

export interface GetStartedProps {
	state: GetStartedState;
	/**
	 * The reader's choice to hide or show the list, which the page keeps (`useSectionOpen`), so it can
	 * show the first-run state again while the list is hidden.
	 */
	fold?: { open: boolean; onOpenChange: (open: boolean) => void };
}

/** Whether the list shows: it knows the reader's facts, and a setup step is not done yet. */
export function getStartedOpen(state: GetStartedState): boolean {
	return state.status === "ready" && state.setup.some((row) => row.status !== "done");
}

const INFO =
	"Each row shows your real state in this workspace, so it changes by itself. The list goes away once every step under Set up is done. Hide it at any time; its heading stays.";

/**
 * The steps between a new member and their first practice review, then ways to learn the page. The
 * setup rows hold the list open and come from the server's facts. When all of them are done, the list
 * goes, and only its status line stays, to say so. It shows nothing while it does not know yet,
 * because most readers never see it, and a placeholder would push their page down for nothing.
 */
export function GetStarted({ state, fold }: GetStartedProps) {
	const announcement = useDoneAnnouncement(state);
	const status = (
		<p role="status" className="sr-only">
			{announcement}
		</p>
	);
	if (state.status === "loading") {
		return status;
	}
	if (!getStartedOpen(state)) {
		return status;
	}
	const { setup, learn } = state;
	const done = setup.filter((row) => row.status === "done").length;
	return (
		<>
			{status}
			<Section
				id={GET_STARTED_ID}
				size="lg"
				title="Get started"
				description={`${done} of ${setup.length} done`}
				info={<InfoTip label="About Get started">{INFO}</InfoTip>}
				fold={fold}
			>
				<RowGroup title="Set up">
					{setup.map((row) => (
						<SetupRow key={row.id} row={row} />
					))}
				</RowGroup>
				{learn.length > 0 && (
					<RowGroup title="Learn">
						{learn.map((row) => (
							<LearnRow key={row.id} row={row} />
						))}
					</RowGroup>
				)}
			</Section>
		</>
	);
}

function RowGroup({ title, children }: { title: string; children: ReactNode }) {
	const headingId = useId();
	return (
		<div className="flex flex-col gap-2">
			<h3 id={headingId} className="text-sm font-medium text-muted-foreground">
				{title}
			</h3>
			<ul aria-labelledby={headingId} className="rounded-xl border bg-card">
				{children}
			</ul>
		</div>
	);
}

function SetupRow({ row }: { row: GetStartedSetupRow }) {
	return (
		<Item render={<li />} variant="row" className="items-start">
			<ItemContent className="min-w-0 basis-64">
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
		<Item render={<li />} variant="row" className="items-start">
			<ItemContent className="min-w-0 basis-64">
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
 * What the status line says when a setup row turns done while the page is open, such as after the
 * reader's first review lands. Nothing on the first render or on a poll that changed nothing, so a
 * screen reader hears only news. The rows last seen are kept in state, and the route builds new
 * rows on every render, so they are compared by their statuses.
 */
function useDoneAnnouncement(state: GetStartedState): string {
	const setup = state.status === "ready" ? state.setup : undefined;
	const [seen, setSeen] = useState(setup);
	const [message, setMessage] = useState("");
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
