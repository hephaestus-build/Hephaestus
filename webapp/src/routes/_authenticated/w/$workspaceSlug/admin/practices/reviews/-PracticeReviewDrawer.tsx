import {
	queryOptions,
	skipToken,
	useQueries,
	useQuery,
	useQueryClient,
} from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useEffect } from "react";

import {
	getAgentJobOptions,
	getAgentJobQueryKey,
	getArtifactTraceOptions,
	getArtifactTraceQueryKey,
	listGroupsOptions,
	listPracticeReviewFeedbackOptions,
	listPracticeReviewFeedbackQueryKey,
	listPracticeReviewObservationsOptions,
	listPracticeReviewObservationsQueryKey,
	listPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import type { AgentJob, ArtifactTrace, Practice, TracedSignal } from "@/api/types.gen";
import { FeedbackLevel } from "@/components/admin/practice-reviews/FeedbackLevel";
import { ObservationLevel } from "@/components/admin/practice-reviews/ObservationLevel";
import { PracticeLevel } from "@/components/admin/practice-reviews/PracticeLevel";
import {
	feedbackLevel,
	PRACTICE_REVIEW_LEVEL_LABELS,
	type PracticeReviewLevel,
	parseWorkLevel,
	practiceLevel,
} from "@/components/admin/practice-reviews/review-levels";
import { rangeScope } from "@/components/admin/practice-reviews/review-outcomes";
import {
	REVIEW_RANGE_DEFS,
	type ReviewRange,
	reviewRangeStart,
} from "@/components/admin/practice-reviews/review-range";
import {
	ACTIVE_REVIEW_POLL_MS,
	REVIEW_PREVIEW_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { toSectionState } from "@/components/admin/practice-reviews/review-states";
import { ReviewedWorkLevel } from "@/components/admin/practice-reviews/ReviewedWorkLevel";
import { ReviewLevelHeader } from "@/components/admin/practice-reviews/ReviewLevelHeader";
import { ReviewRunLevel } from "@/components/admin/practice-reviews/ReviewRunLevel";
import { MissingRecordEmpty } from "@/components/common/MissingRecordEmpty";
import { panelState } from "@/components/common/panel-state";
import { useNow } from "@/components/common/use-now";
import {
	type DetailStackEntry,
	detailStackKey,
	stackInSearch,
} from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import { TraceRefusalAlert } from "@/components/practice-trace/TraceRefusalAlert";
import { DrawerBody } from "@/components/ui/drawer";
import {
	useApprovalQueue,
	useFeedbackController,
	useNextInApprovalQueue,
} from "@/hooks/use-feedback-controller";
import { useObservationController } from "@/hooks/use-observation-controller";
import { usePracticeReviewOverview } from "@/hooks/use-practice-review-overview";
import { useRequestPracticeReview } from "@/hooks/use-request-practice-review";
import { useReviewRunController } from "@/hooks/use-review-run-controller";
import { problemStatusOf } from "@/lib/problem-detail";
import { useSearchState } from "@/lib/search-params";
import { QUERY_RETRIES, sessionRetriesQueries } from "@/runtime/tanstack-query/query-defaults";

export interface PracticeReviewDrawerProps {
	workspaceSlug: string;
	stack: PracticeReviewLevel[];
	/** Opens a level over the one in front, for an opener that cannot be a link. */
	onOpen: (entry: DetailStackEntry) => void;
	onClose: (depth: number) => void;
	/** The range a practice level counts. */
	range: ReviewRange;
	/** The feedback level in front is the approval queue, opened as one from Needs you. */
	approvalQueue: boolean;
}

/**
 * The records Practice reviews opens, as levels over its tabs. Each level reads its own record, so
 * this host fetches only what every level shares: the workspace's practices, for the hover card on
 * a practice's name and a practice level's title.
 */
export function PracticeReviewDrawer({
	workspaceSlug,
	stack,
	onOpen,
	onClose,
	range,
	approvalQueue,
}: PracticeReviewDrawerProps) {
	const practicesQuery = useQuery({
		...listPracticesOptions({ path: { workspaceSlug } }),
		enabled: stack.length > 0,
	});
	const practices = practicesQuery.data;
	const labelOf = (entry: PracticeReviewLevel) =>
		(entry.kind === "practice"
			? practices?.find((practice) => practice.slug === entry.id)?.name
			: undefined) ?? PRACTICE_REVIEW_LEVEL_LABELS[entry.kind];
	const pathAt = levelPathAt(stack, { pageLabel: "Practice reviews", labelOf, onClose });
	const setSearch = useSearchState();
	const router = useRouter();
	const moveOn = (decided: string, next: string | undefined) => {
		if (next === undefined) {
			if (stillInFront(router.latestLocation.search, decided)) {
				onClose(0);
			}
			return;
		}
		// Written over the current history entry and keeping its mark: see `OpenInStackOptions.swap`.
		void setSearch(
			(previous) =>
				stillInFront(previous, decided)
					? { ...previous, detail: [detailStackKey(feedbackLevel(next))] }
					: previous,
			{ state: true, replace: true },
		);
	};

	return (
		<DetailDrawerStack stack={stack} size="detailWide" onClose={onClose}>
			{(entry, level) => {
				const shared = {
					workspaceSlug,
					nested: level.nested,
					path: pathAt(level.depth),
					practices,
				};
				switch (entry.kind) {
					case "review": {
						return <ReviewLevelRead {...shared} jobId={entry.id} />;
					}
					case "observation": {
						return <ObservationLevelRead {...shared} observationId={entry.id} />;
					}
					case "feedback": {
						return (
							<FeedbackLevelRead
								{...shared}
								feedbackId={entry.id}
								inQueue={approvalQueue}
								onMoveOn={(next) => moveOn(entry.id, next)}
							/>
						);
					}
					case "work": {
						return (
							<WorkLevelRead
								{...shared}
								id={entry.id}
								onOpenPractice={(practiceSlug) => onOpen(practiceLevel(practiceSlug))}
							/>
						);
					}
					case "practice": {
						return <PracticeLevelRead {...shared} practiceSlug={entry.id} range={range} />;
					}
				}
			}}
		</DetailDrawerStack>
	);
}

/**
 * A decision moves on only from where it was taken: the decided feedback alone in the address, as
 * it is now rather than as this render was given it. By the time the decision lands the admin may
 * have stepped to another proposal, opened a record over this one or dismissed it, and moving on
 * then would override the step, drop the record or reopen the level.
 */
function stillInFront(search: Record<string, unknown>, feedbackId: string) {
	const current = stackInSearch(search.detail);
	return current.length === 1 && current[0] === detailStackKey(feedbackLevel(feedbackId));
}

interface LevelReadProps {
	workspaceSlug: string;
	nested: boolean;
	path: LevelPath;
	practices: Practice[] | undefined;
}

function ReviewLevelRead({ jobId, ...props }: LevelReadProps & { jobId: string }) {
	const run = useReviewRunController(props.workspaceSlug, jobId);
	return <ReviewRunLevel {...props} {...run} />;
}

function ObservationLevelRead({
	observationId,
	workspaceSlug,
	...props
}: LevelReadProps & { observationId: string }) {
	const controller = useObservationController(workspaceSlug, observationId);
	return <ObservationLevel {...props} {...controller} />;
}

/**
 * Feedback opened as the approval queue reads the queue too, so a decision moves on to the next
 * proposal in it — or, once nothing else waits, closes the level.
 */
function FeedbackLevelRead({
	feedbackId,
	workspaceSlug,
	inQueue,
	onMoveOn,
	...props
}: LevelReadProps & {
	feedbackId: string;
	inQueue: boolean;
	/** Swaps this level for the `next` proposal in the queue, or closes it on none. */
	onMoveOn: (next: string | undefined) => void;
}) {
	const nextAfterDecision = useNextInApprovalQueue(workspaceSlug, feedbackId);
	const moveOn = async () => {
		if (!inQueue) {
			return;
		}
		let next = queue?.next;
		if (next === undefined) {
			// Nothing shown after it — the last of the page read, one reached by skipping others, or a
			// queue not read yet — so whatever still waits is read, and the level closes on none, or
			// when that read fails.
			try {
				next = await nextAfterDecision();
			} catch {
				next = undefined;
			}
		}
		onMoveOn(next);
	};
	const controller = useFeedbackController(workspaceSlug, feedbackId, {
		onDecided: () => {
			void moveOn();
		},
	});
	const awaitingApproval =
		controller.feedback.status === "ready" &&
		controller.feedback.feedback.deliveryState === "AWAITING_APPROVAL";
	const queue = useApprovalQueue(workspaceSlug, feedbackId, inQueue && awaitingApproval);
	return <FeedbackLevel {...props} {...controller} queue={queue} />;
}

/**
 * Three independent reads of the same work, kept independent all the way to the level: each part
 * shows its own result, so one endpoint failing costs the reader that part and not the level. The
 * trace is asked for without a review, which is every review of the work at once.
 */
function WorkLevelRead({
	id,
	onOpenPractice,
	...props
}: LevelReadProps & { id: string; onOpenPractice: (practiceSlug: string) => void }) {
	const work = parseWorkLevel(id);
	const path = { workspaceSlug: props.workspaceSlug };
	const query = { ...work, size: REVIEW_PREVIEW_SIZE };
	const feedbackOptions = listPracticeReviewFeedbackOptions({ path, query });
	const observationOptions = listPracticeReviewObservationsOptions({ path, query });
	const traceOptions = getArtifactTraceOptions({
		path: { ...path, artifactKind: work?.artifactKind ?? "", artifactId: work?.artifactId ?? 0 },
	});
	const feedback = useQuery({
		...feedbackOptions,
		queryFn: work === undefined ? skipToken : feedbackOptions.queryFn,
	});
	const observations = useQuery({
		...observationOptions,
		queryFn: work === undefined ? skipToken : observationOptions.queryFn,
	});
	const trace = useQuery({
		...traceOptions,
		queryFn: work === undefined ? skipToken : traceOptions.queryFn,
		// A 404 is work nothing was ever recorded about: asking again answers the same.
		retry: (failureCount, error) =>
			sessionRetriesQueries() && problemStatusOf(error) !== 404 && failureCount < QUERY_RETRIES,
		// Current practices or latest occasions waiting for admission keep the trace discoverable.
		refetchInterval: (result) => (reviewing(result.state.data) ? ACTIVE_REVIEW_POLL_MS : false),
	});
	// An observation can settle before composition and result processing finish. Follow the runs
	// that own current answers and the latest admitted occasions, even when older observations mask them.
	const reviewIds = [
		...new Set(
			[
				...(trace.data?.practices ?? []),
				...latestSignals(trace.data?.signals.filter((entry) => entry.reviewId !== undefined) ?? []),
			].flatMap((entry) => (entry.reviewId === undefined ? [] : [entry.reviewId])),
		),
	].sort();
	const reviewProgress = useQueries({
		queries: reviewIds.map((jobId) =>
			queryOptions({
				...getAgentJobOptions({ path: { ...path, jobId } }),
				refetchOnMount: "always",
				refetchInterval: (result) =>
					processingReview(result.state.data) ? ACTIVE_REVIEW_POLL_MS : false,
			}),
		),
		combine: (results) => ({
			active: results.some((result) => processingReview(result.data)),
			settled: results
				.flatMap((result) =>
					result.isSuccess && result.isFetchedAfterMount && !processingReview(result.data)
						? [
								`${result.data.id}:${result.data.status}:${result.data.deliveryStatus ?? ""}:${result.data.retryCount}:${result.data.completedAt?.getTime() ?? ""}`,
							]
						: [],
				)
				.sort()
				.join(","),
		}),
	});
	const { settled: settledRuns, active } = reviewProgress;
	const processing = active || reviewing(trace.data);
	const queryClient = useQueryClient();
	const { workspaceSlug } = props;
	const artifactKind = work?.artifactKind;
	const artifactId = work?.artifactId;
	const reviewIdsKey = reviewIds.join(",");
	const assessmentVersion = trace.data?.practices
		.map(
			(entry) =>
				`${entry.practiceSlug}:${entry.reviewId ?? ""}:${entry.outcome}:${entry.decidedAt?.getTime() ?? ""}`,
		)
		.sort()
		.join("|");
	// A retry can reuse a settled run's ID. Compare assessment values, not the DTO's Date objects,
	// so a refreshed but unchanged trace does not refresh its jobs in a cycle.
	useEffect(() => {
		if (assessmentVersion === undefined || assessmentVersion === "" || reviewIdsKey === "") {
			return;
		}
		for (const jobId of reviewIdsKey.split(",")) {
			void queryClient.invalidateQueries({
				queryKey: getAgentJobQueryKey({ path: { workspaceSlug, jobId } }),
			});
		}
	}, [assessmentVersion, reviewIdsKey, queryClient, workspaceSlug]);
	useEffect(() => {
		if (!settledRuns || artifactKind === undefined || artifactId === undefined) {
			return;
		}
		const scope = {
			path: { workspaceSlug },
			query: { artifactKind, artifactId, size: REVIEW_PREVIEW_SIZE },
		};
		void queryClient.invalidateQueries({ queryKey: listPracticeReviewFeedbackQueryKey(scope) });
		void queryClient.invalidateQueries({ queryKey: listPracticeReviewObservationsQueryKey(scope) });
		void queryClient.invalidateQueries({
			queryKey: getArtifactTraceQueryKey({
				path: { workspaceSlug, artifactKind, artifactId },
			}),
		});
	}, [settledRuns, queryClient, workspaceSlug, artifactKind, artifactId]);
	const groups = useQuery({
		...listGroupsOptions({ path }),
		enabled: work !== undefined,
	});
	// The level shows the one piece of work it asks about, so a refusal is said on it.
	const review = useRequestPracticeReview(props.workspaceSlug, { showsInline: () => true });
	if (work === undefined) {
		return (
			<>
				<ReviewLevelHeader
					nested={props.nested}
					path={props.path}
					kind="work"
					title="Unknown work"
				/>
				<DrawerBody className="pt-2">
					<MissingRecordEmpty title="This link does not name a piece of reviewed work" />
				</DrawerBody>
			</>
		);
	}
	return (
		<ReviewedWorkLevel
			{...props}
			{...work}
			feedback={toSectionState(feedback, processing)}
			observations={toSectionState(observations, processing)}
			trace={
				problemStatusOf(trace.error) === 404
					? { status: "none" }
					: panelState(trace, (data) => ({ status: "ready" as const, trace: data }))
			}
			groups={groups.data ?? []}
			onOpenPractice={onOpenPractice}
			onReviewNow={() => review.ask(work)}
			requesting={review.asking !== undefined}
			refusal={
				review.refusal && (
					<TraceRefusalAlert
						refusal={review.refusal.outcome}
						workspaceSlug={props.workspaceSlug}
						canAdminister
					/>
				)
			}
		/>
	);
}

function PracticeLevelRead({
	practiceSlug,
	range,
	practices,
	...props
}: LevelReadProps & { practiceSlug: string; range: ReviewRange }) {
	const nowMs = useNow();
	const from = reviewRangeStart(nowMs, range);
	const overview = usePracticeReviewOverview(props.workspaceSlug, from);
	// The most recent, in the list's own order: what the practice is doing now.
	const observations = useQuery({
		...listPracticeReviewObservationsOptions({
			path: { workspaceSlug: props.workspaceSlug },
			query: { practiceSlug: [practiceSlug], from, size: REVIEW_PREVIEW_SIZE },
		}),
	});
	return (
		<PracticeLevel
			{...props}
			practiceSlug={practiceSlug}
			practice={practices?.find((practice) => practice.slug === practiceSlug)}
			rangeLabel={REVIEW_RANGE_DEFS[range].label}
			scope={rangeScope(from, nowMs)}
			counts={
				overview.status === "ready"
					? {
							status: "ready",
							stale: overview.stale,
							counts: overview.overview.practices.find(
								(counts) => counts.practiceSlug === practiceSlug,
							),
						}
					: overview
			}
			observations={toSectionState(observations)}
		/>
	);
}

/** Follow current assessments and latest occasions still waiting for a review to be admitted. */
function reviewing(trace: ArtifactTrace | undefined): boolean {
	return (
		trace?.practices.some((entry) => entry.outcome === "PENDING" || entry.outcome === "RUNNING") ===
			true ||
		latestSignals(trace?.signals ?? []).some(
			(entry) =>
				entry.state === "RECORDED" || entry.state === "PENDING" || entry.state === "DEFERRED",
		)
	);
}

/** Keep every tie: provider timestamps can have less precision than separate review occasions. */
function latestSignals(signals: TracedSignal[]): TracedSignal[] {
	const latest = new Map<string, TracedSignal[]>();
	for (const signal of signals) {
		const existing = latest.get(signal.signal);
		const at = signal.occurredAt.getTime();
		const previous = existing?.[0]?.occurredAt.getTime();
		if (previous === undefined || at > previous) {
			latest.set(signal.signal, [signal]);
		} else if (at === previous) {
			existing?.push(signal);
		}
	}
	return [...latest.values()].flat();
}

function processingReview(job: AgentJob | undefined): boolean {
	return job?.status === "QUEUED" || job?.status === "RUNNING" || job?.deliveryStatus === "PENDING";
}
