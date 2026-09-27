import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import * as WebBrowser from "expo-web-browser";
import { Platform, StyleSheet, View } from "react-native";

import { getObservationOptions, getObservationQueryKey } from "@/api/@tanstack/react-query.gen";
import type { EvidenceCitation, ObservationDetail } from "@/api/types.gen";
import { FeedbackAnswer } from "@/feedback/FeedbackAnswer";
import { useFeedbackAnswer } from "@/feedback/use-feedback-answer";
import { workLabel } from "@/feedback/work-kind";
import { observationResult, openableUrl } from "@/practice/review-history";
import { reviewHistoryKey } from "@/practice/use-review-history";
import { DIFF_SIDE, NOT_CURRENT, NOT_LIVE_ORIGIN, SEVERITY } from "@/practice/vocabulary";
import { setPendingReport } from "@/report/report";
import { AppText } from "@/ui/AppText";
import { formatDate } from "@/ui/dates";
import { Icon } from "@/ui/Icon";
import { Markdown } from "@/ui/Markdown";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section, SectionText } from "@/ui/Section";
import { radius, space, usePalette } from "@/ui/theme";
import { useWorkspace } from "@/workspace/workspace-context";

/**
 * One observation, whole: what the review found and why, the exact lines it quoted from the work, how
 * current the review rules behind it are, the work itself, and the developer's answer to the feedback
 * it was delivered as. It is opened from a group's or practice's history, and the server returns its delivered feedback handle with the observation. The server shows it only to
 * the developer it is about.
 */
export default function Observation() {
	const { observationId, groupSlug } = useLocalSearchParams<{
		observationId: string;
		groupSlug: string;
	}>();
	const { workspaceSlug } = useWorkspace();
	const detail = useQuery(getObservationOptions({ path: { workspaceSlug, observationId } }));
	return (
		<>
			<Stack.Screen options={{ title: "Observation", headerLargeTitleEnabled: false }} />
			<Screen
				testID="observation-detail"
				onRefresh={() => {
					void detail.refetch();
				}}
				refreshing={detail.isRefetching}
			>
				<QueryStates
					query={detail}
					loadingLabel="Loading this observation"
					errorTitle="Could not load this observation"
					errorMessage="It may no longer be shown, or the connection failed. Try again."
				>
					{(observation) => <Content observation={observation} groupSlug={groupSlug} />}
				</QueryStates>
			</Screen>
		</>
	);
}

function Content({
	observation,
	groupSlug,
}: {
	observation: ObservationDetail;
	groupSlug: string;
}) {
	const router = useRouter();
	const queryClient = useQueryClient();
	const { workspaceSlug, providerType } = useWorkspace();
	const feedbackId = observation.feedbackResponse?.feedbackId;
	const result = observationResult(observation);
	const work = openableUrl(observation.artifactUrl);
	const kind = workLabel(observation.artifactKind, providerType);
	const citations = observation.evidence?.citations ?? [];
	const origin = observation.origin === "LIVE" ? "" : NOT_LIVE_ORIGIN[observation.origin];
	return (
		<>
			<View style={styles.header}>
				<AppText variant="footnote" weight="semibold" tone="accent">
					{observation.practiceName}
				</AppText>
				<AppText variant="title" accessibilityRole="header">
					{observation.summary}
				</AppText>
				<AppText variant="subheadline" weight="semibold">
					{[
						result.label,
						observation.severity === undefined ? undefined : SEVERITY[observation.severity].label,
					]
						.filter((part) => part !== undefined)
						.join(" · ")}
				</AppText>
				<AppText variant="footnote" tone="secondaryLabel">
					{`Observed ${formatDate(observation.observedAt)} on a ${kind}`}
				</AppText>
			</View>
			{observation.claimCurrentness === "CURRENT" ? null : (
				<Notice
					title={NOT_CURRENT[observation.claimCurrentness].label}
					message={NOT_CURRENT[observation.claimCurrentness].description}
				/>
			)}
			{observation.deliveredFeedback === undefined ? null : (
				<Section title="What to try next">
					<SectionText>
						<Markdown markdown={observation.deliveredFeedback} testID="observation-feedback" />
					</SectionText>
				</Section>
			)}
			{observation.evidenceRationale === undefined ? null : (
				<Section title="Why this was noted">
					<SectionText>
						<AppText>{observation.evidenceRationale}</AppText>
					</SectionText>
				</Section>
			)}
			{citations.length === 0 ? null : (
				<Section
					title="Evidence"
					footer="Quoted exactly from the work as the review read it, with where each quote is."
				>
					{citations.map((citation) => (
						<Citation
							key={`${citation.path}:${citation.startLine}-${citation.endLine}:${citation.side ?? ""}`}
							citation={citation}
							detector={observation.evidence?.detector}
						/>
					))}
				</Section>
			)}
			{/* An unlinked work is said in words: a row with a link icon would look like one that opens. */}
			{work === undefined ? (
				<AppText variant="footnote" tone="secondaryLabel" style={styles.aside}>
					{[`This ${kind} could not be linked.`, origin].filter((part) => part !== "").join(" ")}
				</AppText>
			) : (
				<Section footer={origin === "" ? undefined : origin}>
					<Row
						testID="open-work"
						title={`Open the ${kind}`}
						icon={{ ios: "safari", android: "open_in_browser" }}
						kind="action"
						accessibilityHint="Opens the reviewed work in your browser"
						onPress={() => {
							void WebBrowser.openBrowserAsync(work);
						}}
					/>
				</Section>
			)}
			{feedbackId === undefined ? null : (
				<Answer
					feedbackId={feedbackId}
					onChanged={() => {
						void queryClient.invalidateQueries({
							queryKey: reviewHistoryKey(workspaceSlug, groupSlug),
						});
						void queryClient.invalidateQueries({
							queryKey: getObservationQueryKey({
								path: { workspaceSlug, observationId: observation.id },
							}),
						});
					}}
				/>
			)}
			{observation.deliveredFeedback === undefined ? null : (
				<Section footer="If this feedback is offensive, harmful or wrong, tell the people who run this Hephaestus.">
					<Row
						testID="report-observation"
						title="Report this feedback"
						icon={{ ios: "flag", android: "flag" }}
						kind="action"
						onPress={() => {
							setPendingReport({
								subject: "practice-feedback",
								text: `${observation.summary}\n\n${observation.deliveredFeedback ?? ""}`,
							});
							router.push("/report");
						}}
					/>
				</Section>
			)}
		</>
	);
}

/** An explanation that changes how far to trust what follows, set apart but never alarming. */
function Notice({ title, message }: { title: string; message: string }) {
	const palette = usePalette();
	return (
		<View
			style={[styles.notice, { backgroundColor: palette.fill }]}
			accessible
			accessibilityLabel={`${title}. ${message}`}
		>
			<Icon
				name={{ ios: "clock.badge.exclamationmark", android: "history_toggle_off" }}
				size={20}
				tintColor={palette.warning}
			/>
			<View style={styles.noticeText}>
				<AppText variant="subheadline" weight="semibold">
					{title}
				</AppText>
				<AppText variant="subheadline" tone="secondaryLabel">
					{message}
				</AppText>
			</View>
		</View>
	);
}

/** The detector that withholds what looks like a credential, as the web names it. */
const SECRET_SCANNER = "secret-diff-scanner";

/** One quote from the work and exactly where it is: the file, its lines, and which side of the change. */
function Citation({
	citation,
	detector,
}: {
	citation: EvidenceCitation;
	detector: string | undefined;
}) {
	const palette = usePalette();
	const lines =
		citation.endLine === citation.startLine
			? `line ${citation.startLine}`
			: `lines ${citation.startLine}–${citation.endLine}`;
	const where = [
		citation.path,
		lines,
		citation.side === undefined ? undefined : DIFF_SIDE[citation.side],
	]
		.filter((part) => part !== undefined)
		.join(" · ");
	return (
		<SectionText>
			<AppText variant="footnote" tone="secondaryLabel" selectable>
				{where}
			</AppText>
			{citation.quoteRedacted || citation.quote === undefined ? (
				<AppText variant="subheadline" tone="secondaryLabel">
					{detector === SECRET_SCANNER
						? "Not quoted. This looked like a credential, so the text was never stored — the path and line above are where it sits."
						: "Not quoted. The passage was withheld, so only its location was kept."}
				</AppText>
			) : (
				<View style={[styles.quote, { backgroundColor: palette.fill }]}>
					<AppText variant="footnote" style={styles.code} selectable>
						{citation.quote}
					</AppText>
				</View>
			)}
		</SectionText>
	);
}

const styles = StyleSheet.create({
	header: { gap: space.sm },
	aside: { paddingHorizontal: space.lg },
	notice: {
		flexDirection: "row",
		gap: space.md,
		padding: space.lg,
		borderRadius: radius.card,
		borderCurve: "continuous",
	},
	noticeText: { flex: 1, gap: space.xs },
	quote: { padding: space.md, borderRadius: radius.control, borderCurve: "continuous" },
	code: { fontFamily: Platform.OS === "ios" ? "Menlo" : "monospace" },
});

/** Mounted only after this route has loaded a feedback record. */
function Answer({ feedbackId, onChanged }: { feedbackId: string; onChanged?: () => void }) {
	const answer = useFeedbackAnswer(feedbackId, onChanged);
	return <FeedbackAnswer {...answer} />;
}
