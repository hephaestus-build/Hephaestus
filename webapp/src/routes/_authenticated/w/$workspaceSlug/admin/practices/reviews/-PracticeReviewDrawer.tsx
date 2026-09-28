import { skipToken, useQuery } from "@tanstack/react-query";

import {
	listPracticeReviewFeedbackOptions,
	listPracticeReviewObservationsOptions,
	listPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import type { Practice } from "@/api/types.gen";
import {
	ACTIVITY_RANGE_DEFS,
	type ActivityRange,
	rangeStart,
} from "@/components/activity/activity-range";
import { FeedbackLevel } from "@/components/admin/practice-reviews/FeedbackLevel";
import { ObservationLevel } from "@/components/admin/practice-reviews/ObservationLevel";
import { PracticeLevel } from "@/components/admin/practice-reviews/PracticeLevel";
import {
	PRACTICE_REVIEW_LEVEL_LABELS,
	type PracticeReviewLevel,
	parseWorkLevel,
} from "@/components/admin/practice-reviews/review-levels";
import { rangeScope } from "@/components/admin/practice-reviews/review-outcomes";
import { REVIEW_PREVIEW_SIZE } from "@/components/admin/practice-reviews/review-search";
import { toSectionState } from "@/components/admin/practice-reviews/review-states";
import { ReviewedWorkLevel } from "@/components/admin/practice-reviews/ReviewedWorkLevel";
import { ReviewLevelHeader } from "@/components/admin/practice-reviews/ReviewLevelHeader";
import { ReviewRunLevel } from "@/components/admin/practice-reviews/ReviewRunLevel";
import { MissingRecordEmpty } from "@/components/common/MissingRecordEmpty";
import { useNow } from "@/components/common/use-now";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import { DrawerBody } from "@/components/ui/drawer";
import { useFeedbackController } from "@/hooks/use-feedback-controller";
import { useObservationController } from "@/hooks/use-observation-controller";
import { usePracticeReviewOverview } from "@/hooks/use-practice-review-overview";
import { useReviewRunController } from "@/hooks/use-review-run-controller";

export interface PracticeReviewDrawerProps {
	workspaceSlug: string;
	stack: PracticeReviewLevel[];
	onClose: (depth: number) => void;
	/** The range a practice level counts. */
	range: ActivityRange;
}

/**
 * The records Practice reviews opens, as levels over its tabs. Each level reads its own record, so
 * this host fetches only what every level shares: the workspace's practices, for the hover card on
 * a practice's name and a practice level's title.
 */
export function PracticeReviewDrawer({
	workspaceSlug,
	stack,
	onClose,
	range,
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
						return <FeedbackLevelRead {...shared} feedbackId={entry.id} />;
					}
					case "work": {
						return <WorkLevelRead {...shared} id={entry.id} />;
					}
					case "practice": {
						return <PracticeLevelRead {...shared} practiceSlug={entry.id} range={range} />;
					}
				}
			}}
		</DetailDrawerStack>
	);
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

function FeedbackLevelRead({
	feedbackId,
	workspaceSlug,
	...props
}: LevelReadProps & { feedbackId: string }) {
	const controller = useFeedbackController(workspaceSlug, feedbackId);
	return <FeedbackLevel {...props} {...controller} />;
}

/**
 * Two independent reads of the same work, kept independent all the way to the level: each section
 * shows its own result, so one endpoint failing costs the reader that section and not the level.
 */
function WorkLevelRead({ id, ...props }: LevelReadProps & { id: string }) {
	const work = parseWorkLevel(id);
	const path = { workspaceSlug: props.workspaceSlug };
	const query = { ...work, size: REVIEW_PREVIEW_SIZE };
	const feedbackOptions = listPracticeReviewFeedbackOptions({ path, query });
	const observationOptions = listPracticeReviewObservationsOptions({ path, query });
	const feedback = useQuery({
		...feedbackOptions,
		queryFn: work === undefined ? skipToken : feedbackOptions.queryFn,
	});
	const observations = useQuery({
		...observationOptions,
		queryFn: work === undefined ? skipToken : observationOptions.queryFn,
	});
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
			feedback={toSectionState(feedback)}
			observations={toSectionState(observations)}
		/>
	);
}

function PracticeLevelRead({
	practiceSlug,
	range,
	practices,
	...props
}: LevelReadProps & { practiceSlug: string; range: ActivityRange }) {
	const nowMs = useNow();
	const from = rangeStart(nowMs, range);
	const overview = usePracticeReviewOverview(props.workspaceSlug, from);
	const observations = useQuery({
		...listPracticeReviewObservationsOptions({
			path: { workspaceSlug: props.workspaceSlug },
			query: {
				practiceSlug: [practiceSlug],
				from,
				sort: "ACTIONABILITY",
				size: REVIEW_PREVIEW_SIZE,
			},
		}),
	});
	return (
		<PracticeLevel
			{...props}
			practiceSlug={practiceSlug}
			practice={practices?.find((practice) => practice.slug === practiceSlug)}
			rangeLabel={ACTIVITY_RANGE_DEFS[range].label}
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
