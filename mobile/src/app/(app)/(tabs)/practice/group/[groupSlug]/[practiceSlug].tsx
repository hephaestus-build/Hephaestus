import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { StyleSheet, View } from "react-native";

import type { PracticeStandingObservation } from "@/api/types.gen";
import { workLabel } from "@/feedback/work-kind";
import { Direction } from "@/practice/Direction";
import { PracticesOff } from "@/practice/PracticesOff";
import { type PracticeEntry, practiceNextStep } from "@/practice/profile";
import { loadedRuns } from "@/practice/review-history";
import { ReviewHistoryPreview, RunSection } from "@/practice/ReviewHistory";
import { StandingLabel } from "@/practice/Standing";
import { usePracticeProfile } from "@/practice/use-practice-profile";
import { useReviewHistory } from "@/practice/use-review-history";
import { useReviewNavigation } from "@/practice/use-review-navigation";
import { SEVERITY, STANDING } from "@/practice/vocabulary";
import { AppText } from "@/ui/AppText";
import type { IconName } from "@/ui/Icon";
import { Markdown } from "@/ui/Markdown";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section, SectionText } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { space } from "@/ui/theme";
import { useWorkspace } from "@/workspace/workspace-context";

/**
 * One practice: where the developer stands in it, the next step, every observation behind the
 * standing, why the practice matters, and the latest reviews that mention it.
 */
export default function PracticeDetail() {
	const { groupSlug, practiceSlug } = useLocalSearchParams<{
		groupSlug: string;
		practiceSlug: string;
	}>();
	const { practicesEnabled, displayName } = useWorkspace();
	const profile = usePracticeProfile();
	const group = profile.data?.find((candidate) => candidate.group.slug === groupSlug);
	const practice = group?.practices.find((candidate) => candidate.slug === practiceSlug);
	return (
		<>
			<Stack.Screen
				options={{ title: practice?.name ?? "Practice", headerLargeTitleEnabled: false }}
			/>
			<Screen
				testID="practice-detail"
				onRefresh={profile.refetch}
				refreshing={profile.isRefetching}
			>
				{practicesEnabled ? (
					<QueryStates
						query={profile}
						loadingLabel="Loading this practice"
						errorTitle="Could not load this practice"
						empty={{
							when: () => practice === undefined,
							view: (
								<StateView
									state="empty"
									icon={{ ios: "questionmark.folder", android: "folder_off" }}
									title="This practice is no longer shown"
									message="It may have been moved, renamed or no longer be reviewed in this workspace."
								/>
							),
						}}
					>
						{() =>
							practice === undefined || group === undefined ? null : (
								<Practice practice={practice} groupName={group.group.name} />
							)
						}
					</QueryStates>
				) : (
					<PracticesOff workspaceName={displayName} />
				)}
			</Screen>
		</>
	);
}

function Practice({ practice, groupName }: { practice: PracticeEntry; groupName: string }) {
	const workspace = useWorkspace();
	const history = useReviewHistory(
		practice.groupSlug,
		practice.slug,
		practice.standing !== "NOT_OBSERVED",
	);
	const navigation = useReviewNavigation(practice.groupSlug, practice.slug);
	const next = practiceNextStep(practice);
	const toWorkOn = practice.verdict?.toWorkOn ?? [];
	const strengths = practice.verdict?.strengths ?? [];
	return (
		<>
			<View style={styles.header}>
				<AppText variant="footnote" weight="semibold" tone="secondaryLabel">
					{groupName}
				</AppText>
				<StandingLabel standing={practice.standing} variant="headline" />
				<AppText variant="subheadline" tone="secondaryLabel">
					{STANDING[practice.standing].description}
				</AppText>
			</View>
			{next === undefined ? null : (
				<Section title="Your next step">
					<SectionText>
						<AppText>{next}</AppText>
					</SectionText>
				</Section>
			)}
			<Observations
				title="To work on"
				observations={toWorkOn}
				icon={{ ios: "exclamationmark.circle", android: "error" }}
			/>
			<Observations
				title="Going well"
				observations={strengths}
				icon={{ ios: "checkmark.circle", android: "check_circle" }}
			/>
			{practice.standing === "NOT_OBSERVED" ? null : (
				<Direction
					direction={practice.verdict?.direction}
					support={practice.verdict?.trendSupport}
					scope="practice"
				/>
			)}
			{practice.whyItMatters === undefined ? null : (
				<Section title="Why it matters">
					<SectionText>
						<Markdown markdown={practice.whyItMatters} />
					</SectionText>
				</Section>
			)}
			{practice.whatGoodLooksLike === undefined ? null : (
				<Section title="What good looks like">
					<SectionText>
						<Markdown markdown={practice.whatGoodLooksLike} />
					</SectionText>
				</Section>
			)}
			{practice.standing === "NOT_OBSERVED" ? null : (
				<ReviewHistoryPreview
					query={{
						...history,
						data: history.data === undefined ? undefined : loadedRuns(history.data),
					}}
					hasNextPage={history.hasNextPage}
					scope="practice"
					onAllHistory={navigation.onAllHistory}
					renderRun={(run) => (
						<RunSection
							key={run.reviewId}
							run={run}
							providerType={workspace.providerType}
							scope="practice"
							onObservationPress={navigation.onObservationPress}
							onOpenWork={navigation.onOpenWork}
						/>
					)}
				/>
			)}
		</>
	);
}

/** Every observation on one side of the standing, each opening its evidence; none is hidden. */
function Observations({
	title,
	observations,
	icon,
}: {
	title: string;
	observations: PracticeStandingObservation[];
	icon: IconName;
}) {
	const router = useRouter();
	const { providerType } = useWorkspace();
	if (observations.length === 0) {
		return null;
	}
	return (
		<Section title={title}>
			{observations.map((observation) => (
				<Row
					key={observation.observationId}
					testID={`observation-${observation.observationId}`}
					icon={icon}
					title={observation.title}
					subtitle={[
						observation.severity === undefined ? undefined : SEVERITY[observation.severity].label,
						workLabel(observation.workKind, providerType),
						observation.locator,
					]
						.filter((part) => part !== undefined && part !== "")
						.join(" · ")}
					onPress={() =>
						router.push({
							pathname: "/practice/observation/[observationId]",
							params: { observationId: observation.observationId },
						})
					}
				/>
			))}
		</Section>
	);
}

const styles = StyleSheet.create({
	header: { gap: space.sm },
});
