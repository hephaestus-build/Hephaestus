import type { ReactNode } from "react";
import { Pressable, StyleSheet, View } from "react-native";

import type { ObservationDetail, PracticeGroupReviewRun } from "@/api/types.gen";
import { AppText } from "@/ui/AppText";
import { formatRelative } from "@/ui/dates";
import type { IconName } from "@/ui/Icon";
import { QueryStates, type QueryLike } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Section, SectionItem } from "@/ui/Section";
import { space, usePalette } from "@/ui/theme";

import { observationResult, openableUrl, workHeading } from "./review-history";
import { FEEDBACK_RESOLUTION, SEVERITY } from "./vocabulary";

export interface ReviewHistoryPreviewProps {
	query: QueryLike<PracticeGroupReviewRun[]>;
	hasNextPage: boolean;
	scope: "group" | "practice";
	renderRun: (run: PracticeGroupReviewRun) => ReactNode;
	onAllHistory: () => void;
}

/** How many of the latest review runs a group or practice page shows before "All review history". */
const PREVIEW = 2;

/**
 * The latest review runs in a group or practice, each with the observations it recorded, and the way
 * to the whole history, which has a screen of its own so a long history is never laid out here.
 */
export function ReviewHistoryPreview({
	query,
	hasNextPage,
	scope,
	renderRun,
	onAllHistory,
}: ReviewHistoryPreviewProps) {
	return (
		<View style={styles.history}>
			<AppText variant="title" accessibilityRole="header">
				Recent reviews
			</AppText>
			<QueryStates
				query={query}
				loadingLabel="Loading your recent reviews"
				errorTitle="Could not load your recent reviews"
				empty={{
					when: (loaded) => loaded.length === 0,
					view: <AppText tone="secondaryLabel">{emptyHistory(scope)}</AppText>,
				}}
			>
				{(loaded) => (
					<View style={styles.runs}>
						{loaded.slice(0, PREVIEW).map(renderRun)}
						{/* The whole history is offered only when there is more of it than shown here. */}
						{loaded.length <= PREVIEW && !hasNextPage ? null : (
							<Section>
								<Row
									testID="history-all"
									title="All review history"
									icon={{ ios: "clock.arrow.circlepath", android: "history" }}
									onPress={onAllHistory}
								/>
							</Section>
						)}
					</View>
				)}
			</QueryStates>
		</View>
	);
}

/** Whose history this is: the group, and the practice when it is narrowed to one. */
export function ReviewHistoryHeader({
	groupName,
	practiceName,
	scope,
}: {
	groupName: string | undefined;
	practiceName: string | undefined;
	scope: "group" | "practice";
}) {
	return (
		<View style={styles.scope}>
			<AppText variant="title" accessibilityRole="header">
				{practiceName ?? groupName ?? "Review history"}
			</AppText>
			<AppText variant="subheadline" tone="secondaryLabel">
				{scope === "group"
					? "Every review of your work that recorded something in this group, newest first."
					: `Every review of your work that recorded something about this practice${
							groupName === undefined ? "" : `, in ${groupName}`
						}, newest first.`}
			</AppText>
		</View>
	);
}

/** What an empty history says: nothing recorded yet, never that the work was good. */
export function emptyHistory(scope: "group" | "practice"): string {
	return scope === "group"
		? "No review of your work has recorded anything in this group yet."
		: "No review of your work has recorded anything about this practice yet.";
}

const RESULT_GLYPH: Record<"positive" | "negative" | "none", IconName> = {
	positive: { ios: "checkmark.circle", android: "check_circle" },
	negative: { ios: "exclamationmark.circle", android: "error" },
	none: { ios: "circle.dashed", android: "radio_button_unchecked" },
};

/** One review run: the reviewed work, which opens it, and each observation, which opens its detail. */
export function RunSection({
	run,
	providerType,
	scope,
	onObservationPress,
	onOpenWork,
}: {
	run: PracticeGroupReviewRun;
	providerType: Parameters<typeof workHeading>[1];
	scope: "group" | "practice";
	onObservationPress: (observationId: string) => void;
	onOpenWork: (url: string) => void;
}) {
	const palette = usePalette();
	const heading = workHeading(run.reviewedWork, providerType);
	const url = openableUrl(run.reviewedWork.url);
	const meta = `${heading.detail} · reviewed ${formatRelative(run.reviewedAt)}`;
	const header = (
		<View style={styles.header}>
			<AppText variant="headline">{heading.title}</AppText>
			<AppText variant="footnote" tone="secondaryLabel">
				{meta}
			</AppText>
		</View>
	);
	return (
		<Section>
			<SectionItem>
				{url === undefined ? (
					<View accessible accessibilityLabel={`${heading.title}, ${meta}`}>
						{header}
					</View>
				) : (
					<Pressable
						accessibilityRole="link"
						accessibilityLabel={`${heading.title}, ${meta}`}
						accessibilityHint="Opens the work in your browser"
						onPress={() => onOpenWork(url)}
						style={({ pressed }) => (pressed ? { backgroundColor: palette.fill } : undefined)}
					>
						{header}
					</Pressable>
				)}
			</SectionItem>
			{run.observations.map((observation) => (
				<Row
					key={observation.id}
					testID={`observation-${observation.id}`}
					icon={RESULT_GLYPH[resultKind(observation)]}
					title={observation.summary}
					subtitle={observationLine(observation, scope === "group")}
					onPress={() => onObservationPress(observation.id)}
				/>
			))}
		</Section>
	);
}

function resultKind(observation: ObservationDetail): "positive" | "negative" | "none" {
	const { positive } = observationResult(observation);
	if (positive === undefined) {
		return "none";
	}
	return positive ? "positive" : "negative";
}

/** "Include tests · Negative outcome · Major · You answered: Addressed" */
function observationLine(observation: ObservationDetail, withPractice: boolean): string {
	return [
		withPractice ? observation.practiceName : undefined,
		observationResult(observation).label,
		observation.severity === undefined ? undefined : SEVERITY[observation.severity].label,
		observation.feedbackResponse?.resolution === undefined
			? undefined
			: `You answered: ${FEEDBACK_RESOLUTION[observation.feedbackResponse.resolution].label}`,
	]
		.filter((part) => part !== undefined)
		.join(" · ");
}

const styles = StyleSheet.create({
	history: { gap: space.md },
	runs: { gap: space.lg },
	header: { padding: space.lg, gap: space.xs },
	scope: { gap: space.xs },
});
