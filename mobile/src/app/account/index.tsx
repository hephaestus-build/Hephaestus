import { useQuery } from "@tanstack/react-query";
import { Stack, useRouter } from "expo-router";
import * as WebBrowser from "expo-web-browser";
import { Alert, StyleSheet, View } from "react-native";

import { Avatar } from "@/account/Avatar";
import { getCurrentUserOptions, listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { webUrl } from "@/instance/instance";
import { signOut, useSession } from "@/session/session-store";
import { AppText } from "@/ui/AppText";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";
import { space } from "@/ui/theme";
import { useSelectedWorkspace } from "@/workspace/workspace-store";

/**
 * The account, as a sheet over whatever the person was doing: who is signed in, to which Hephaestus
 * and workspace, and everything about the account itself. It needs no workspace, so someone without
 * one reaches the same screens.
 */
export default function Account() {
	const router = useRouter();
	const session = useSession();
	const user = useQuery(getCurrentUserOptions({}));
	const workspaces = useQuery(listWorkspacesOptions({}));
	const selected = useSelectedWorkspace();
	const instance = session.status === "signedIn" ? session.instance : undefined;
	const name = user.data?.displayName ?? user.data?.username;
	const list = workspaces.data ?? [];
	const current = list.find((workspace) => workspace.workspaceSlug === selected);

	return (
		<>
			<Stack.Screen options={{ title: "Account" }} />
			<Screen
				testID="account-screen"
				onRefresh={() => {
					void user.refetch();
					void workspaces.refetch();
				}}
				refreshing={user.isRefetching || workspaces.isRefetching}
			>
				{/* The account at the head of its settings: picture above name, centred, at every text size. */}
				<View style={styles.profile}>
					<Avatar loading={user.isPending} url={user.data?.avatarUrl} size={AVATAR} />
					<AppText variant="title" accessibilityRole="header" style={styles.centred}>
						{user.isError ? "Your account" : (name ?? " ")}
					</AppText>
					<AppText variant="subheadline" tone="secondaryLabel" style={styles.centred}>
						{user.data?.username === undefined
							? instance?.label
							: `@${user.data.username} · ${instance?.label ?? ""}`}
					</AppText>
				</View>
				{/* Switching needs a second workspace; with one, the row only says which is open. */}
				{current === undefined ? null : (
					<Section>
						<Row
							testID="account-workspace"
							title="Workspace"
							subtitle={current.displayName}
							icon={{ ios: "square.stack.3d.up", android: "workspaces" }}
							{...(list.length > 1 ? { onPress: () => router.push("/account/workspaces") } : {})}
						/>
					</Section>
				)}
				<Section>
					<Row
						testID="account-notifications"
						title="Notifications"
						icon={{ ios: "bell", android: "notifications" }}
						onPress={() => router.push("/account/notifications")}
					/>
					<Row
						testID="account-sessions"
						title="Signed-in devices"
						icon={{ ios: "iphone", android: "devices" }}
						onPress={() => router.push("/account/sessions")}
					/>
					<Row
						testID="account-privacy"
						title="Privacy and account"
						icon={{ ios: "hand.raised", android: "privacy_tip" }}
						onPress={() => router.push("/account/privacy")}
					/>
				</Section>
				<Section>
					<Row
						testID="account-about"
						title="About Hephaestus"
						icon={{ ios: "info.circle", android: "info" }}
						onPress={() => router.push("/account/about")}
					/>
					{instance === undefined ? null : (
						<Row
							title="Open Hephaestus on the web"
							subtitle={instance.label}
							icon={{ ios: "safari", android: "open_in_browser" }}
							kind="action"
							onPress={() => {
								void WebBrowser.openBrowserAsync(webUrl(instance));
							}}
						/>
					)}
				</Section>
				<Section>
					<Row
						testID="sign-out"
						title="Sign out"
						kind="destructive"
						onPress={() =>
							Alert.alert(
								"Sign out of Hephaestus?",
								"This phone stops receiving notifications until you sign in again.",
								[
									{ text: "Cancel", style: "cancel" },
									{
										text: "Sign out",
										style: "destructive",
										onPress: () => {
											void signOut();
										},
									},
								],
							)
						}
					/>
				</Section>
			</Screen>
		</>
	);
}

const AVATAR = 72;

const styles = StyleSheet.create({
	profile: { alignItems: "center", gap: space.xs, paddingBottom: space.sm },
	// Stretched to the column so a long name wraps rather than running off at large text sizes.
	centred: { textAlign: "center", alignSelf: "stretch" },
});
