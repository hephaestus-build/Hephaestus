import { Stack, useLocalSearchParams } from "expo-router";
import { ActivityIndicator, FlatList, RefreshControl, StyleSheet, View } from "react-native";

import { PracticesOff } from "@/practice/PracticesOff";
import { loadedRuns } from "@/practice/review-history";
import { emptyHistory, ReviewHistoryHeader, RunSection } from "@/practice/ReviewHistory";
import { usePracticeProfile } from "@/practice/use-practice-profile";
import { useReviewHistory } from "@/practice/use-review-history";
import { useReviewNavigation } from "@/practice/use-review-navigation";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { useColumnPadding } from "@/ui/layout";
import { StaleBanner } from "@/ui/QueryStates";
import { StateView } from "@/ui/StateView";
import { space, usePalette } from "@/ui/theme";
import { useWorkspace } from "@/workspace/workspace-context";

/**
 * Every review run in a group, or about one practice in it, newest first. A virtualized list of its
 * own, so however long the history grows only what is near the screen is laid out; the next page loads
 * as the end comes near, and a failed page offers to try again where it failed.
 */
export default function ReviewHistoryScreen() {
	const { groupSlug, practiceSlug } = useLocalSearchParams<{
		groupSlug: string;
		practiceSlug?: string;
	}>();
	const { practicesEnabled, displayName, providerType } = useWorkspace();
	const palette = usePalette();
	const column = useColumnPadding();
	const history = useReviewHistory(groupSlug, practiceSlug, practicesEnabled);
	const runs = loadedRuns(history.data);
	const navigation = useReviewNavigation(groupSlug, practiceSlug);
	const profile = usePracticeProfile();
	const entry = profile.data?.find((group) => group.group.slug === groupSlug);
	const practice = entry?.practices.find((candidate) => candidate.slug === practiceSlug);
	const scope = practiceSlug === undefined ? "group" : "practice";
	const loadMore = () => {
		if (history.hasNextPage && !history.isFetchingNextPage && !history.isFetchNextPageError) {
			void history.fetchNextPage();
		}
	};

	let empty;
	if (!practicesEnabled) {
		empty = <PracticesOff workspaceName={displayName} />;
	} else if (history.data !== undefined) {
		empty = (
			<StateView
				state="empty"
				icon={{ ios: "clock", android: "history" }}
				title="No reviews yet"
				message={emptyHistory(scope)}
			/>
		);
	} else if (history.isError) {
		empty = (
			<StateView
				state="error"
				title="Could not load your review history"
				message="Check your connection and try again."
				onRetry={() => {
					void history.refetch();
				}}
			/>
		);
	} else {
		empty = <StateView state="loading" label="Loading your review history" />;
	}

	let footer = null;
	if (history.isFetchNextPageError) {
		footer = (
			<View style={styles.footer} accessibilityLiveRegion="polite">
				<AppText tone="secondaryLabel">Could not load earlier reviews.</AppText>
				<Button
					title="Try again"
					variant="secondary"
					onPress={() => {
						void history.fetchNextPage();
					}}
				/>
			</View>
		);
	} else if (history.isFetchingNextPage) {
		footer = (
			<View style={styles.footer} accessible accessibilityLabel="Loading earlier reviews">
				<ActivityIndicator />
			</View>
		);
	} else if (runs.length > 0 && !history.hasNextPage) {
		footer = (
			<AppText variant="footnote" tone="secondaryLabel" style={styles.end}>
				That is every review so far.
			</AppText>
		);
	}

	return (
		<>
			<Stack.Screen options={{ title: "Review history", headerLargeTitleEnabled: false }} />
			<FlatList
				testID="review-history"
				data={runs}
				keyExtractor={(run) => run.reviewId}
				renderItem={({ item }) => (
					<RunSection
						run={item}
						providerType={providerType}
						scope={scope}
						onObservationPress={navigation.onObservationPress}
						onOpenWork={navigation.onOpenWork}
					/>
				)}
				ItemSeparatorComponent={Gap}
				ListHeaderComponent={
					<View style={styles.header}>
						<ReviewHistoryHeader
							groupName={entry?.group.name}
							practiceName={practice?.name}
							scope={scope}
						/>
						{history.isError && !history.isFetchNextPageError && history.data !== undefined ? (
							<StaleBanner
								onRetry={() => {
									void history.refetch();
								}}
							/>
						) : null}
					</View>
				}
				ListEmptyComponent={empty}
				ListFooterComponent={footer}
				onEndReached={loadMore}
				onEndReachedThreshold={0.5}
				refreshControl={
					<RefreshControl
						refreshing={history.isRefetching && !history.isFetchingNextPage}
						onRefresh={() => {
							void history.refetch();
						}}
					/>
				}
				contentInsetAdjustmentBehavior="automatic"
				style={{ backgroundColor: palette.groupedBackground }}
				contentContainerStyle={[styles.content, column]}
			/>
		</>
	);
}

function Gap() {
	return <View style={styles.gap} />;
}

const styles = StyleSheet.create({
	content: { paddingTop: space.lg, paddingBottom: space.xl * 2 },
	header: { gap: space.md, paddingBottom: space.lg },
	gap: { height: space.lg },
	footer: { alignItems: "center", gap: space.md, paddingVertical: space.xl },
	end: { textAlign: "center", paddingVertical: space.xl },
});
