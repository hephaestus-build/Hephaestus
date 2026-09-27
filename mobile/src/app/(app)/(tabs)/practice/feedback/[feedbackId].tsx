import { useQuery } from "@tanstack/react-query";
import { Stack, useFocusEffect, useLocalSearchParams, useRouter } from "expo-router";
import * as WebBrowser from "expo-web-browser";
import { useCallback } from "react";
import { StyleSheet, View } from "react-native";

import { getInAppFeedbackOptions } from "@/api/@tanstack/react-query.gen";
import { FeedbackAnswer } from "@/feedback/FeedbackAnswer";
import { useFeedbackAnswer } from "@/feedback/use-feedback-answer";
import { workIcon } from "@/feedback/work-kind";
import { HephSays } from "@/heph/HephSays";
import { FEEDBACK_OFFER, speaks } from "@/heph/mentor-voice";
import { useHephAccess } from "@/heph/use-heph-access";
import { openableUrl, workHeading } from "@/practice/review-history";
import { setPendingReport } from "@/report/report";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { formatRelative } from "@/ui/dates";
import { Markdown } from "@/ui/Markdown";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section, SectionText } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { radius, space, usePalette } from "@/ui/theme";
import { useWorkspace } from "@/workspace/workspace-context";

const TALK_HINT =
	"Opens a new conversation with Heph, with a draft about this feedback. Nothing is sent until you send it.";

/**
 * One piece of feedback. Opened from the list, it shows what that read returned and asks for nothing
 * more. Opened directly — from a notification, or after the app restarted — it reads the list once
 * while it is on screen, which is the person asking for this feedback, and so delivers it.
 */
export default function FeedbackDetail() {
	const { feedbackId } = useLocalSearchParams<{ feedbackId: string }>();
	const router = useRouter();
	const workspace = useWorkspace();
	const { access } = useHephAccess();
	const palette = usePalette();
	const list = useQuery({
		...getInAppFeedbackOptions({ path: { workspaceSlug: workspace.workspaceSlug } }),
		enabled: false,
	});
	const feedback = list.data?.find((item) => item.id === feedbackId);
	const loaded = list.data !== undefined;
	const { refetch } = list;
	useFocusEffect(
		useCallback(() => {
			if (!loaded) {
				void refetch();
			}
		}, [loaded, refetch]),
	);

	if (feedback === undefined) {
		let state;
		if (list.isError) {
			state = (
				<StateView
					state="error"
					title="Could not load this feedback"
					message="Check your connection and try again."
					onRetry={() => {
						void refetch();
					}}
				/>
			);
		} else if (loaded) {
			state = (
				<StateView
					state="empty"
					icon={{ ios: "tray", android: "inbox" }}
					title="This feedback is no longer shown"
					message="It may have been replaced by newer feedback about the same habit. Practice feedback has what is current."
				/>
			);
		} else {
			state = <StateView state="loading" label="Loading this feedback" />;
		}
		return (
			<Screen>
				<Stack.Screen options={{ title: "Feedback", headerLargeTitleEnabled: false }} />
				{state}
			</Screen>
		);
	}

	const talkItThrough = () =>
		router.push({
			pathname: "/conversation/[threadId]",
			params: { threadId: "new", about: feedback.id },
		});

	return (
		<Screen testID="feedback-detail">
			<Stack.Screen options={{ title: "Feedback", headerLargeTitleEnabled: false }} />
			<View style={styles.header}>
				<AppText variant="footnote" weight="semibold" tone="accent">
					{feedback.practiceName}
				</AppText>
				<AppText variant="title" accessibilityRole="header">
					{feedback.headline}
				</AppText>
				<AppText variant="footnote" tone="secondaryLabel">
					{[feedback.groupName, formatRelative(feedback.preparedAt)]
						.filter((part) => part !== undefined)
						.join(" · ")}
				</AppText>
			</View>
			<View style={[styles.body, { backgroundColor: palette.card }]}>
				<Markdown markdown={feedback.body} testID="feedback-body" />
			</View>
			{/* Heph offers help where the reader decides what to do next, and only where it can talk. */}
			{speaks(access) ? (
				<HephSays
					testID="feedback-offer"
					action={
						<Button
							testID="talk-it-through"
							title="Talk it through"
							variant="secondary"
							accessibilityHint={TALK_HINT}
							onPress={talkItThrough}
						/>
					}
				>
					{FEEDBACK_OFFER}
				</HephSays>
			) : null}
			{feedback.groupSlug === undefined ? null : (
				<Section footer="Where you stand in this practice, and every observation behind it.">
					<Row
						testID="feedback-practice"
						title={feedback.practiceName}
						subtitle={feedback.groupName}
						icon={{ ios: "list.bullet.clipboard", android: "checklist" }}
						onPress={() =>
							router.push({
								pathname: "/practice/group/[groupSlug]/[practiceSlug]",
								params: {
									groupSlug: feedback.groupSlug ?? "",
									practiceSlug: feedback.practiceSlug,
								},
							})
						}
					/>
				</Section>
			)}
			{feedback.evidence.length === 0 ? null : (
				<Section title="Seen on" footer="Open the work in your code host to see where it happened.">
					{feedback.evidence.map((item) => {
						const work = item.reviewedWork;
						const heading = workHeading(work, workspace.providerType);
						const url = openableUrl(work.url);
						return (
							<Row
								key={`${work.kind}-${work.id}`}
								icon={workIcon(work.kind)}
								title={item.summary ?? heading.title}
								subtitle={`${heading.detail} · ${formatRelative(item.observedAt)}`}
								onPress={
									url === undefined
										? undefined
										: () => {
												void WebBrowser.openBrowserAsync(url);
											}
								}
							/>
						);
					})}
				</Section>
			)}
			{feedback.whyItMatters === undefined ? null : (
				<Section title="Why it matters">
					<SectionText>
						<Markdown markdown={feedback.whyItMatters} />
					</SectionText>
				</Section>
			)}
			{feedback.whatGoodLooksLike === undefined ? null : (
				<Section title="What good looks like">
					<SectionText>
						<Markdown markdown={feedback.whatGoodLooksLike} />
					</SectionText>
				</Section>
			)}
			<Answer feedbackId={feedback.id} />
			<Section footer="If this feedback is offensive, harmful or wrong, tell the people who run this Hephaestus.">
				<Row
					testID="report-feedback"
					title="Report this feedback"
					icon={{ ios: "flag", android: "flag" }}
					kind="action"
					onPress={() => {
						setPendingReport({
							subject: "practice-feedback",
							text: `${feedback.headline}\n\n${feedback.body}`,
						});
						router.push("/report");
					}}
				/>
			</Section>
		</Screen>
	);
}

const styles = StyleSheet.create({
	header: { gap: space.sm },
	body: { padding: space.lg, borderRadius: radius.card, borderCurve: "continuous" },
});

/** Mounted only after this route has loaded a feedback record. */
function Answer({ feedbackId }: { feedbackId: string }) {
	const answer = useFeedbackAnswer(feedbackId);
	return <FeedbackAnswer {...answer} />;
}
