import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { StyleSheet, View } from "react-native";

import { Direction } from "@/practice/Direction";
import { groupVisual } from "@/practice/group-visual";
import { PracticesOff } from "@/practice/PracticesOff";
import { basedOn, type GroupEntry } from "@/practice/profile";
import { loadedRuns } from "@/practice/review-history";
import { ReviewHistoryPreview, RunSection } from "@/practice/ReviewHistory";
import { GroupGlyph, STANDING_GLYPH, StandingLabel } from "@/practice/Standing";
import { usePracticeProfile } from "@/practice/use-practice-profile";
import { useReviewHistory } from "@/practice/use-review-history";
import { useReviewNavigation } from "@/practice/use-review-navigation";
import { STANDING, TREND } from "@/practice/vocabulary";
import { AppText } from "@/ui/AppText";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section, SectionText } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { space } from "@/ui/theme";
import { useWorkspace } from "@/workspace/workspace-context";

/**
 * One practice group: where the developer stands in it and what that rests on, the next step the
 * server suggests, each practice in it, and the latest reviews behind it.
 */
export default function PracticeGroup() {
	const { groupSlug } = useLocalSearchParams<{ groupSlug: string }>();
	const { practicesEnabled, displayName } = useWorkspace();
	const profile = usePracticeProfile();
	const entry = profile.data?.find((candidate) => candidate.group.slug === groupSlug);
	return (
		<>
			{/* The name is the header's; the page opens with the group's glyph and where the developer stands. */}
			<Stack.Screen
				options={{ title: entry?.group.name ?? "Practice group", headerLargeTitleEnabled: false }}
			/>
			<Screen testID="practice-group" onRefresh={profile.refetch} refreshing={profile.isRefetching}>
				{practicesEnabled ? (
					<QueryStates
						query={profile}
						loadingLabel="Loading this group"
						errorTitle="Could not load this group"
						empty={{
							when: () => entry === undefined,
							view: (
								<StateView
									state="empty"
									icon={{ ios: "questionmark.folder", android: "folder_off" }}
									title="This group is no longer shown"
									message="It may have been renamed, hidden or removed from this workspace."
								/>
							),
						}}
					>
						{() => (entry === undefined ? null : <Group entry={entry} />)}
					</QueryStates>
				) : (
					<PracticesOff workspaceName={displayName} />
				)}
			</Screen>
		</>
	);
}

function Group({ entry }: { entry: GroupEntry }) {
	const router = useRouter();
	const workspace = useWorkspace();
	const { group, verdict } = entry;
	const history = useReviewHistory(group.slug, undefined, entry.practices.length > 0);
	const navigation = useReviewNavigation(group.slug);
	const evidence = basedOn(verdict?.sources, workspace.providerType);
	const guidance = verdict?.guidance?.trim();
	return (
		<>
			<View style={styles.header}>
				<View style={styles.title}>
					<GroupGlyph visual={groupVisual(group.icon, group.color)} size={28} />
					<StandingLabel standing={entry.standing} variant="headline" />
				</View>
				<AppText variant="subheadline" tone="secondaryLabel">
					{STANDING[entry.standing].description}
				</AppText>
				{evidence === undefined ? null : (
					<AppText variant="footnote" tone="secondaryLabel">
						{evidence}
					</AppText>
				)}
			</View>
			{guidance === undefined || guidance === "" ? null : (
				<Section
					title="Suggested next step"
					footer="From recent reviews of your work in this group."
				>
					<SectionText>
						<AppText>{guidance}</AppText>
					</SectionText>
				</Section>
			)}
			<Section
				title="Practices"
				footer={
					entry.practices.length === 0
						? undefined
						: "Open a practice for its next step and the observations behind it."
				}
			>
				{entry.practices.length === 0 ? (
					<SectionText>
						<AppText tone="secondaryLabel">
							This workspace reviews no practice in this group yet.
						</AppText>
					</SectionText>
				) : (
					entry.practices.map((practice) => {
						const direction = practice.verdict?.direction;
						return (
							<Row
								key={practice.slug}
								testID={`practice-${practice.slug}`}
								icon={STANDING_GLYPH[practice.standing]}
								title={practice.name}
								subtitle={[
									STANDING[practice.standing].label,
									direction === undefined || direction === "INSUFFICIENT_EVIDENCE"
										? undefined
										: TREND[direction].label,
								]
									.filter((part) => part !== undefined)
									.join(" · ")}
								onPress={() =>
									router.push({
										pathname: "/practice/group/[groupSlug]/[practiceSlug]",
										params: { groupSlug: group.slug, practiceSlug: practice.slug },
									})
								}
							/>
						);
					})
				)}
			</Section>
			{/* A group with no observations has no direction to speak of; it would only say so. What to do comes
			    first, and the evidence for the direction after it. */}
			{entry.standing === "NOT_OBSERVED" ? null : (
				<Direction direction={verdict?.direction} support={verdict?.trendSupport} scope="group" />
			)}
			{entry.practices.length === 0 ? null : (
				<ReviewHistoryPreview
					query={{
						...history,
						data: history.data === undefined ? undefined : loadedRuns(history.data),
					}}
					hasNextPage={history.hasNextPage}
					scope="group"
					onAllHistory={navigation.onAllHistory}
					renderRun={(run) => (
						<RunSection
							key={run.reviewId}
							run={run}
							providerType={workspace.providerType}
							scope="group"
							onObservationPress={navigation.onObservationPress}
							onOpenWork={navigation.onOpenWork}
						/>
					)}
				/>
			)}
			{group.description === undefined ? null : (
				<Section title="About this group">
					<SectionText>
						<AppText>{group.description}</AppText>
					</SectionText>
				</Section>
			)}
		</>
	);
}

const styles = StyleSheet.create({
	header: { gap: space.sm },
	title: { flexDirection: "row", alignItems: "center", gap: space.md },
});
