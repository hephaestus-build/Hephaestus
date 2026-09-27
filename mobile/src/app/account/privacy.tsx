import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Stack } from "expo-router";
import * as WebBrowser from "expo-web-browser";
import { useState } from "react";
import { StyleSheet, Switch, TextInput, View } from "react-native";

import {
	deleteCurrentUserMutation,
	getConsentStatusOptions,
	getConsentStatusQueryKey,
	getCurrentUserOptions,
	updateResearchConsentMutation,
} from "@/api/@tanstack/react-query.gen";
import { webUrl } from "@/instance/instance";
import { signOut, useSession } from "@/session/session-store";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { InlineError } from "@/ui/InlineError";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";
import { radius, space, usePalette } from "@/ui/theme";

/** The same phrase the web asks for: deletion is typed, not tapped. */
const DELETE_CONFIRM_PHRASE = "delete my account";

/**
 * Privacy and the account itself. Reachable from any signed-in session — before the transparency
 * notice is accepted, and without a workspace — because the server lets a person read and delete their
 * account at any time. Research answers change only once the notice is complete.
 */
export default function Privacy() {
	const session = useSession();
	const palette = usePalette();
	const queryClient = useQueryClient();
	const consent = useQuery(getConsentStatusOptions({}));
	const user = useQuery(getCurrentUserOptions({}));
	const research = useMutation({
		...updateResearchConsentMutation(),
		onSuccess: (status) => queryClient.setQueryData(getConsentStatusQueryKey({}), status),
	});
	const deletion = useMutation({
		...deleteCurrentUserMutation(),
		onSuccess: () => {
			void signOut();
		},
	});
	const [confirm, setConfirm] = useState("");
	const instance = session.status === "signedIn" ? session.instance : undefined;
	const userId = user.data?.id;

	return (
		<>
			<Stack.Screen options={{ title: "Privacy and account", headerLargeTitleEnabled: false }} />
			<Screen testID="privacy-screen">
				{instance === undefined ? null : (
					<Section footer="Who runs this Hephaestus, what it stores and for how long.">
						<Row
							title="Privacy notice"
							kind="action"
							icon={{ ios: "doc.text", android: "description" }}
							onPress={() => {
								void WebBrowser.openBrowserAsync(`${webUrl(instance)}/privacy`);
							}}
						/>
						<Row
							title="Export your data"
							kind="action"
							icon={{ ios: "square.and.arrow.up", android: "ios_share" }}
							onPress={() => {
								void WebBrowser.openBrowserAsync(`${webUrl(instance)}/settings`);
							}}
							accessibilityHint="Opens your settings on the web, where the export is prepared and downloaded"
						/>
					</Section>
				)}
				<QueryStates
					query={consent}
					loadingLabel="Loading your research answer"
					errorTitle="Could not load your research answer"
				>
					{(status) =>
						!status.completed ||
						status.researchOrganization === undefined ||
						status.researchOrganization === "" ? null : (
							<Section
								title="Research"
								footer={`Optional, and Hephaestus works the same either way. The research is run by ${status.researchOrganization}.`}
							>
								<View style={[styles.toggle, { backgroundColor: palette.card }]}>
									<AppText style={styles.toggleLabel} nativeID="research-label">
										Take part in the research
									</AppText>
									<Switch
										testID="research-toggle"
										value={status.participateInResearch}
										disabled={research.isPending}
										accessibilityLabelledBy="research-label"
										accessibilityLabel="Take part in the research"
										onValueChange={(granted) =>
											research.mutate({
												body: {
													noticeVersion: status.noticeVersion,
													granted,
													researchOrganization: status.researchOrganization,
												},
											})
										}
									/>
								</View>
							</Section>
						)
					}
				</QueryStates>
				{research.isError ? (
					<AppText tone="danger" accessibilityLiveRegion="assertive">
						Your answer was not saved. Try again.
					</AppText>
				) : null}
				<View style={[styles.danger, { backgroundColor: palette.card }]}>
					<AppText variant="headline" accessibilityRole="header">
						Delete account
					</AppText>
					<AppText variant="subheadline" tone="secondaryLabel">
						This signs you out everywhere and disables your account at once. After a cooldown of
						about 48 hours, your account and the data tied to it are deleted; you cannot undo it
						from here. Work you did in connected repositories, and what Hephaestus recorded about
						that work, is not erased with the account: the privacy notice says how to ask for that
						too. To confirm, type “{DELETE_CONFIRM_PHRASE}” below.
					</AppText>
					<TextInput
						testID="delete-confirm"
						value={confirm}
						onChangeText={setConfirm}
						placeholder={DELETE_CONFIRM_PHRASE}
						placeholderTextColor={palette.tertiaryLabel}
						autoCapitalize="none"
						autoCorrect={false}
						accessibilityLabel="Confirmation phrase"
						style={[styles.input, { color: palette.label, backgroundColor: palette.fill }]}
					/>
					{user.isError ? (
						<InlineError
							testID="account-load-error"
							message="Your account could not be loaded, so it cannot be deleted from here yet."
							retrying={user.isFetching}
							onRetry={() => {
								void user.refetch();
							}}
						/>
					) : null}
					{deletion.isError ? (
						<InlineError
							testID="delete-error"
							message="Your account was not deleted. Nothing changed; you can try again."
						/>
					) : null}
					<Button
						testID="delete-account"
						title="Delete account"
						variant="destructive"
						pending={deletion.isPending}
						disabled={
							confirm.trim().toLowerCase() !== DELETE_CONFIRM_PHRASE || userId === undefined
						}
						onPress={() => {
							if (userId !== undefined) {
								deletion.mutate({ headers: { "X-Confirm-Delete": String(userId) } });
							}
						}}
					/>
				</View>
			</Screen>
		</>
	);
}

const styles = StyleSheet.create({
	toggle: { flexDirection: "row", alignItems: "center", gap: space.md, padding: space.lg },
	toggleLabel: { flex: 1 },
	danger: {
		padding: space.lg,
		gap: space.md,
		borderRadius: radius.card,
		borderCurve: "continuous",
	},
	input: {
		minHeight: 46,
		borderRadius: radius.control,
		borderCurve: "continuous",
		paddingHorizontal: space.lg,
		fontSize: 17,
	},
});
