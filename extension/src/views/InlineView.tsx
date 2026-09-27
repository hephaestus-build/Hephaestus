import { useMutation, useQuery } from "@tanstack/react-query";
import { browser } from "@wxt-dev/browser";
import { useEffect, useRef, useState } from "react";

import { PageReport } from "~/components/report/PageReport";
import type { Loadable, ReportState } from "~/components/report/report-summary";
import { RowPreview } from "~/components/report/RowPreview";
import {
	type FrameToPage,
	MAX_FRAME_HEIGHT,
	OPEN_PARAMETER,
	openTokenSchema,
	pageToFrameSchema,
	WORK_PARAMETER,
} from "~/shared/frame-messages";
import type { ReviewAction } from "~/shared/review-actions";
import {
	refreshInterval,
	type ReviewContext,
	reviewActivity,
	THE_PAGE,
	type WorkSubject,
} from "~/shared/review-context";
import { ask } from "~/ui/rpc-client";
import { applyTheme } from "~/ui/theme";
import { useGeneration } from "~/ui/worker-state";

/** The page that framed this one; the only window it talks to, and only about its size and theme. */
function parentOrigin(): string | undefined {
	return location.ancestorOrigins[0];
}

/** The token the page mounted this frame with; every message back carries it. */
function openToken(): string | undefined {
	const parsed = openTokenSchema.safeParse(
		new URLSearchParams(location.search).get(OPEN_PARAMETER),
	);
	return parsed.success ? parsed.data : undefined;
}

type FrameMessage = { type: "hephaestus:ready" } | { type: "hephaestus:size"; height: number };

function postToPage(message: FrameMessage): void {
	const origin = parentOrigin();
	const open = openToken();
	if (origin !== undefined && open !== undefined) {
		const withToken: FrameToPage = { ...message, open };
		window.parent.postMessage(withToken, origin);
	}
}

/**
 * Tells the page the report's height, which the page sizes the frame to so the report sits in the
 * page's flow with no scrolling of its own, and listens for the page's theme. A list row's preview is
 * one line that never wraps, so what it reports follows the reader's font size, never its content.
 */
function useFrameBridge(root: React.RefObject<HTMLDivElement | null>): void {
	useEffect(() => {
		const element = root.current;
		if (element === null) {
			return;
		}
		const observer = new ResizeObserver(() => {
			postToPage({
				type: "hephaestus:size",
				height: Math.min(MAX_FRAME_HEIGHT, Math.ceil(element.scrollHeight)),
			});
		});
		observer.observe(element);
		const onMessage = (event: MessageEvent) => {
			if (event.source !== window.parent || event.origin !== parentOrigin()) {
				return;
			}
			const message = pageToFrameSchema.safeParse(event.data);
			if (message.success) {
				applyTheme(message.data.theme);
			}
		};
		window.addEventListener("message", onMessage);
		// Rendered and listening: the page shows the frame instead of its generic loading line and
		// answers with its theme, which this listener is now there to receive.
		postToPage({ type: "hephaestus:ready" });
		return () => {
			observer.disconnect();
			window.removeEventListener("message", onMessage);
		};
	}, [root]);
}

/**
 * Whether the report is on screen. While it is scrolled away or its tab pane is hidden it stops
 * asking; the next interval after it returns asks again.
 */
function useOnScreen(): boolean {
	const [onScreen, setOnScreen] = useState(true);
	useEffect(() => {
		const observer = new IntersectionObserver((entries) => {
			const latest = entries.at(-1);
			if (latest !== undefined) {
				setOnScreen(latest.isIntersecting);
			}
		});
		observer.observe(document.documentElement);
		return () => {
			observer.disconnect();
		};
	}, []);
	return onScreen;
}

function openSettings(): void {
	void browser.runtime.openOptionsPage();
}

function loadable<T>(query: {
	data: T | undefined;
	isError: boolean;
	error: Error | null;
}): Loadable<T> {
	// A failed refresh is a failure, not the previous answer passing for a current one: the section
	// says so and offers a retry, and a refused detail takes its private content off screen. A
	// refresh still in flight keeps what it has.
	if (query.isError) {
		return { status: "error", message: query.error?.message ?? "Could not load." };
	}
	if (query.data !== undefined) {
		return { status: "ready", data: query.data };
	}
	return { status: "loading" };
}

/**
 * What this frame's report is about: its tab's own page, or — when the page opened it as a list row's
 * preview — that row's work. The row's address is only a selector; the worker accepts it solely
 * against the tab's actual list.
 */
function frameSubject(): WorkSubject {
	const work = new URLSearchParams(location.search).get(WORK_PARAMETER);
	return work === null ? THE_PAGE : { kind: "list-row", url: work };
}

/**
 * What the reader chose in this tab's report, kept by the worker across provider re-renders and tab
 * switches on the same work; a fresh report starts closed until it answers.
 */
function useRememberedView() {
	const state = useQuery({ queryKey: ["state"], queryFn: async () => ask({ type: "get-state" }) });
	const generation = state.data?.generation;
	const remembered = useQuery({
		queryKey: ["report-view"],
		queryFn: async () => ask({ type: "get-report-view" }),
		staleTime: Number.POSITIVE_INFINITY,
	});
	const [choice, setChoice] = useState<{ expanded: boolean; workspaceSlug?: string }>();
	const remember = (next: { expanded: boolean; workspaceSlug?: string }) => {
		setChoice(next);
		if (generation !== undefined) {
			// Best effort: a choice the worker does not keep only means a fresh report starts closed.
			const keep = async () => {
				try {
					await ask({ type: "set-report-view", generation, ...next });
				} catch {
					// Nothing to show for it.
				}
			};
			void keep();
		}
	};
	return {
		webAppOrigin: state.data?.instance?.webAppOrigin,
		settled: remembered.isFetched,
		expanded: choice?.expanded ?? remembered.data?.expanded ?? false,
		workspaceSlug: choice?.workspaceSlug ?? remembered.data?.workspaceSlug,
		remember,
	};
}

/**
 * The reader's review of the tab's work, or of one list row's: the context, then the reader's own
 * comments on it, both asked again while something is moving and the frame is on screen.
 */
function useWorkReview(subject: WorkSubject, workspaceSlug: string | undefined, enabled: boolean) {
	const onScreen = useOnScreen();
	// The latest context, for the poll below; written after render, read when a query asks for its
	// next interval.
	const latestContext = useRef<ReviewContext>(undefined);
	const context = useQuery({
		queryKey: ["context", workspaceSlug],
		enabled,
		queryFn: async () => ask({ type: "get-context", workspaceSlug, subject }),
		refetchInterval: (query) =>
			query.state.data === undefined || !onScreen ? false : refreshInterval(query.state.data),
	});
	useEffect(() => {
		latestContext.current = context.data;
	}, [context.data]);
	const ready = context.data?.status === "ready" ? context.data : undefined;
	const slug = ready?.workspace.slug ?? "";
	const feedback = useQuery({
		queryKey: ["work-feedback", slug],
		queryFn: async () => ask({ type: "get-work-feedback", workspaceSlug: slug, subject }),
		enabled: ready !== undefined,
		refetchInterval: () =>
			latestContext.current === undefined || !onScreen
				? false
				: refreshInterval(latestContext.current),
	});
	let state: ReportState = { status: "loading" };
	// A failed refresh keeps the last answer on screen, marked as not current; only a first load that
	// fails has nothing to show but the failure.
	if (context.data !== undefined) {
		state = context.data;
	} else if (context.isError) {
		state = { status: "failed", message: context.error.message };
	}
	return {
		state,
		ready,
		feedback: ready === undefined ? undefined : loadable(feedback),
		activity: context.data === undefined ? undefined : reviewActivity(context.data),
		stale:
			context.isError && context.data !== undefined
				? { message: context.error.message }
				: undefined,
		retry: () => {
			void context.refetch();
			if (ready !== undefined) {
				void feedback.refetch();
			}
		},
	};
}

/** A list row's preview: the line, filled at once, and nothing to open. */
function RowContent({ subject }: { subject: WorkSubject }) {
	const webAppOrigin = useQuery({
		queryKey: ["state"],
		queryFn: async () => ask({ type: "get-state" }),
	}).data?.instance?.webAppOrigin;
	const review = useWorkReview(subject, undefined, true);
	return (
		<RowPreview
			state={review.state}
			feedback={review.feedback}
			activity={review.activity}
			webAppOrigin={webAppOrigin}
			onRetry={review.retry}
			onOpenSettings={openSettings}
			stale={review.stale}
		/>
	);
}

/** The report on the work's own page, with the reader's observations once they open it. */
function PageContent() {
	const view = useRememberedView();
	const { expanded, workspaceSlug, remember, webAppOrigin } = view;
	const review = useWorkReview(THE_PAGE, workspaceSlug, view.settled);
	const { ready } = review;
	const slug = ready?.workspace.slug ?? "";
	const observations = useQuery({
		queryKey: ["observations", slug],
		queryFn: async () => ask({ type: "list-observations", workspaceSlug: slug }),
		enabled: ready !== undefined && expanded,
	});
	const action = useMutation({
		mutationFn: async (request: ReviewAction) =>
			ask({ type: "open-action", workspaceSlug: slug, action: request }),
	});
	let actionState: { status: "pending" } | { status: "error"; message: string } | undefined;
	if (action.isPending) {
		actionState = { status: "pending" };
	} else if (action.isError) {
		actionState = { status: "error", message: action.error.message };
	}
	return (
		<PageReport
			state={review.state}
			feedback={review.feedback}
			observations={ready === undefined || !expanded ? undefined : loadable(observations)}
			activity={review.activity}
			webAppOrigin={webAppOrigin}
			expanded={expanded}
			onToggle={() => remember({ expanded: !expanded, workspaceSlug })}
			onRetry={review.retry}
			onRetryObservations={() => {
				void observations.refetch();
			}}
			onOpenSettings={openSettings}
			onChooseWorkspace={(chosen) => remember({ expanded, workspaceSlug: chosen })}
			onAction={(request) => action.mutate(request)}
			action={actionState}
			stale={review.stale}
		/>
	);
}

/**
 * The practice review inside a provider's page, and the extension's main view: on the work's own page
 * the report, on a list the row's preview. It reads, and on the work's page asks the worker to open
 * the extension's confirmation window for a review request; it never makes one itself, because the
 * provider's page could cover or move it to redirect a click. The worker decides what the frame may
 * ask from the tab that holds it, whatever the frame says.
 */
export function InlineView() {
	const root = useRef<HTMLDivElement>(null);
	const generation = useGeneration();
	const [subject] = useState(frameSubject);
	useFrameBridge(root);
	return (
		<div ref={root} className="relative">
			{subject.kind === "page" ? (
				<PageContent key={generation} />
			) : (
				<RowContent key={generation} subject={subject} />
			)}
		</div>
	);
}
