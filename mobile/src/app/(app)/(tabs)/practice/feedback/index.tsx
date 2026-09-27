import { Stack, useRouter } from "expo-router";
import { StyleSheet, View } from "react-native";

import { FeedbackCard } from "@/feedback/FeedbackCard";
import { useInAppFeedback } from "@/feedback/use-in-app-feedback";
import { HephSays } from "@/heph/HephSays";
import { feedbackCount, feedbackNote, speaks } from "@/heph/mentor-voice";
import { useHephAccess } from "@/heph/use-heph-access";
import { statusOf } from "@/session/api-client";
import { AppText } from "@/ui/AppText";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { space } from "@/ui/theme";
import { useWorkspace } from "@/workspace/workspace-context";

/**
 * The developer's practice feedback: the habits reviews of their work suggest they work on. This is the
 * one screen that reads it, and reading it is what delivers it, so it is read only while this screen is
 * in front of the person (`useInAppFeedback`).
 */
export default function PracticeFeedback() {
	const router = useRouter();
	const workspace = useWorkspace();
	const feedback = useInAppFeedback(workspace.workspaceSlug);
	const { access } = useHephAccess();
	const heph = speaks(access);
	const startConversation = (
		<Section>
			<Row
				testID="feedback-start-conversation"
				title="Start a conversation"
				icon={{ ios: "square.and.pencil", android: "edit" }}
				onPress={() =>
					router.push({ pathname: "/conversation/[threadId]", params: { threadId: "new" } })
				}
			/>
		</Section>
	);
	return (
		<>
			<Stack.Screen options={{ title: "Practice feedback", headerLargeTitleEnabled: false }} />
			<Screen
				testID="feedback-screen"
				onRefresh={() => {
					void feedback.refetch();
				}}
				refreshing={feedback.isRefetching}
			>
				<QueryStates
					query={feedback}
					loadingLabel="Loading your feedback"
					errorTitle="Could not load your feedback"
					errorMessage={
						statusOf(feedback.error) === undefined
							? "Check your connection and try again."
							: "Try again in a moment."
					}
					empty={{
						when: (list) => list.length === 0,
						view: heph ? (
							<HephSays testID="feedback-note" action={startConversation}>
								{feedbackNote(0)}
							</HephSays>
						) : (
							<StateView
								state="empty"
								icon={{ ios: "tray", android: "inbox" }}
								title="No feedback to show yet"
								message="Feedback appears here when there is something in your work worth your attention."
							/>
						),
					}}
				>
					{(list) => (
						<View style={styles.list} testID="feedback-list">
							{heph ? (
								<HephSays testID="feedback-note">{feedbackNote(list.length)}</HephSays>
							) : (
								<AppText variant="subheadline" weight="semibold" tone="secondaryLabel">
									{feedbackCount(list.length)}
								</AppText>
							)}
							{/* A plain press, not a Link with a context menu: expo-router's native preview wrapper hides
							    the card from the accessibility tree. Talking it through is offered on the feedback page. */}
							{list.map((item) => (
								<FeedbackCard
									key={item.id}
									feedback={item}
									provider={workspace.providerType}
									onPress={() =>
										router.push({
											pathname: "/practice/feedback/[feedbackId]",
											params: { feedbackId: item.id },
										})
									}
								/>
							))}
						</View>
					)}
				</QueryStates>
			</Screen>
		</>
	);
}

const styles = StyleSheet.create({
	list: { gap: space.md },
});
