import { useMutation } from "@tanstack/react-query";
import { Stack, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { Platform, StyleSheet, TextInput, View } from "react-native";

import { submitInstanceProductFeedbackMutation } from "@/api/@tanstack/react-query.gen";
import { addressLabel } from "@/instance/default-instance";
import { USER_AGENT } from "@/instance/instance";
import { clearPendingReport, peekPendingReport, REPORT_PAGE, reportMessage } from "@/report/report";
import { useSession } from "@/session/session-store";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { InlineError } from "@/ui/InlineError";
import { Screen } from "@/ui/Screen";
import { Section, SectionItem } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { radius, space, usePalette } from "@/ui/theme";

/** iOS puts a sheet's actions in its bar, dismissing leading and committing trailing; Android, in the page. */
const IOS = Platform.OS === "ios";

/** Done once the report is sent: the sheet's task is complete. */
function DoneInBar({ onPress }: { onPress: () => void }) {
	return IOS ? (
		<Stack.Toolbar placement="right">
			<Stack.Toolbar.Button variant="done" onPress={onPress}>
				Done
			</Stack.Toolbar.Button>
		</Stack.Toolbar>
	) : null;
}

/** The system's Close where there is nothing to send: Done would claim a task was completed. */
function CloseInBar({ onPress }: { onPress: () => void }) {
	return IOS ? (
		<Stack.Toolbar placement="right">
			<Stack.Toolbar.Button icon="xmark" accessibilityLabel="Close" onPress={onPress} />
		</Stack.Toolbar>
	) : null;
}

/**
 * Reports something Hephaestus wrote that is offensive, harmful or wrong, from where it was read. It
 * goes to the people who run this Hephaestus through its product feedback, and says so; only the text
 * shown here and the reason go with it.
 */
export default function Report() {
	const router = useRouter();
	const palette = usePalette();
	const session = useSession();
	const [report] = useState(peekPendingReport);
	// Opened with it, the report is this screen's alone; a later one starts empty.
	useEffect(() => {
		clearPendingReport();
	}, []);
	const [reason, setReason] = useState("");
	const send = useMutation(submitInstanceProductFeedbackMutation());
	const instance = session.status === "signedIn" ? addressLabel(session.instance.label) : "";

	if (report === undefined) {
		return (
			<Screen>
				<Stack.Screen options={{ title: "Report" }} />
				<CloseInBar onPress={() => router.back()} />
				<StateView
					state="empty"
					icon={{ ios: "flag", android: "flag" }}
					title="Nothing to report"
					message="Open the reply or feedback you want to report and choose Report there."
				/>
			</Screen>
		);
	}

	if (send.isSuccess) {
		return (
			<Screen testID="report-sent">
				<Stack.Screen options={{ title: "Report" }} />
				<DoneInBar onPress={() => router.back()} />
				<StateView
					state="empty"
					icon={{ ios: "checkmark.circle", android: "check_circle" }}
					title="Reported"
					message={`The people who run ${instance} can now see it. Thank you for saying something.`}
					action={IOS ? undefined : { title: "Done", onPress: () => router.back() }}
				/>
			</Screen>
		);
	}

	const submit = () =>
		send.mutate({
			body: {
				kind: "FEEDBACK",
				message: reportMessage(report, reason),
				pagePath: REPORT_PAGE[report.subject],
				userAgent: USER_AGENT,
			},
		});

	return (
		<Screen testID="report-screen">
			<Stack.Screen options={{ title: "Report" }} />
			{IOS ? (
				<>
					<Stack.Toolbar placement="left">
						<Stack.Toolbar.Button onPress={() => router.back()}>Cancel</Stack.Toolbar.Button>
					</Stack.Toolbar>
					<Stack.Toolbar placement="right">
						<Stack.Toolbar.Button
							variant="prominent"
							disabled={send.isPending}
							accessibilityLabel="Send report"
							onPress={submit}
						>
							{send.isPending ? "Sending…" : "Send"}
						</Stack.Toolbar.Button>
					</Stack.Toolbar>
				</>
			) : null}
			<Section
				title="What is wrong with it"
				footer={`Sent to the people who run ${instance}, with your account: the reported text and your reason, exactly as shown below, and nothing else from the conversation.`}
			>
				<SectionItem>
					<TextInput
						testID="report-reason"
						value={reason}
						onChangeText={setReason}
						placeholder="Offensive, harmful or wrong — say why (optional)"
						placeholderTextColor={palette.tertiaryLabel}
						multiline
						maxLength={1000}
						accessibilityLabel="Why you are reporting it"
						style={[styles.reason, { color: palette.label }]}
					/>
				</SectionItem>
			</Section>
			<Section
				title="Sent exactly as shown"
				footer="Long text is shortened to fit, and the report says so."
			>
				<SectionItem>
					<AppText selectable variant="subheadline" style={styles.excerpt} testID="report-preview">
						{reportMessage(report, reason)}
					</AppText>
				</SectionItem>
			</Section>
			{send.isError ? (
				<InlineError
					testID="report-error"
					message="The report did not go through. You can send it again."
					retrying={send.isPending}
					onRetry={submit}
				/>
			) : null}
			{IOS ? null : (
				<View style={styles.actions}>
					<Button
						testID="report-send"
						title="Send report"
						pending={send.isPending}
						onPress={submit}
					/>
					<Button title="Cancel" variant="plain" onPress={() => router.back()} />
				</View>
			)}
		</Screen>
	);
}

const styles = StyleSheet.create({
	excerpt: { padding: space.lg },
	reason: {
		minHeight: 96,
		padding: space.lg,
		fontSize: 17,
		textAlignVertical: "top",
		borderRadius: radius.card,
	},
	actions: { gap: space.sm },
});
