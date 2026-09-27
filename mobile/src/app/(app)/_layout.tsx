import { useQuery } from "@tanstack/react-query";
import { Stack, useRouter } from "expo-router";
import * as WebBrowser from "expo-web-browser";
import { useEffect } from "react";
import { View } from "react-native";

import { getConsentStatusOptions, listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { webUrl } from "@/instance/instance";
import { useUpdateRequired } from "@/instance/use-update-required";
import { useSessionKey } from "@/session/authority";
import { markConsent, signOut, useSession } from "@/session/session-store";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { tabStackOptions } from "@/ui/navigation";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { space } from "@/ui/theme";
import { WorkspaceProvider } from "@/workspace/workspace-context";
import {
	loadSelectedWorkspace,
	selectWorkspace,
	useSelectedWorkspace,
} from "@/workspace/workspace-store";
import { WorkspaceList } from "@/workspace/WorkspaceList";

/**
 * The signed-in shell: confirms the transparency notice is complete, then resolves which workspace
 * the tabs show. A person in one workspace goes straight in; in several, they choose once.
 */
export default function SignedInLayout() {
	const router = useRouter();
	const session = useSession();
	const sessionKey = useSessionKey();
	const apiBaseUrl = session.status === "signedIn" ? session.instance.apiBaseUrl : "";
	const consent = useQuery(getConsentStatusOptions({}));
	const workspaces = useQuery(listWorkspacesOptions({}));
	const selected = useSelectedWorkspace();
	const updateRequired = useUpdateRequired(apiBaseUrl);
	const instance = session.status === "signedIn" ? session.instance : undefined;

	useEffect(() => {
		if (consent.data?.completed === false) {
			markConsent("required");
		}
	}, [consent.data?.completed]);

	useEffect(() => {
		if (apiBaseUrl !== "") {
			void loadSelectedWorkspace(sessionKey, apiBaseUrl);
		}
	}, [sessionKey, apiBaseUrl]);

	const list = workspaces.data ?? [];
	const only = list.length === 1 ? list[0] : undefined;
	useEffect(() => {
		if (only !== undefined && selected === null) {
			void selectWorkspace(sessionKey, apiBaseUrl, only.workspaceSlug);
		}
	}, [sessionKey, apiBaseUrl, only, selected]);

	if (updateRequired) {
		return (
			<Screen testID="update-required">
				<StateView
					state="empty"
					icon={{ ios: "arrow.down.app", android: "system_update" }}
					title="Update Hephaestus"
					message="Your team's Hephaestus no longer works with this version of the app. Update it from the App Store or Google Play to continue."
				/>
				<Section>
					<Row
						title="Privacy and account"
						icon={{ ios: "hand.raised", android: "privacy_tip" }}
						onPress={() => router.push("/account/privacy")}
					/>
				</Section>
				<Button
					title="Sign out"
					variant="secondary"
					onPress={() => {
						void signOut();
					}}
				/>
			</Screen>
		);
	}

	const current = list.find((workspace) => workspace.workspaceSlug === selected);
	if (current !== undefined) {
		return (
			<WorkspaceProvider workspace={current}>
				{/* Keyed by session and workspace: switching either remounts every tab, so nothing — a
				    stream, a draft, a list — carries from one into the other. */}
				<Stack
					key={`${sessionKey}:${current.workspaceSlug}`}
					screenOptions={{ headerShown: false }}
				>
					<Stack.Screen name="(tabs)" />
					{/* Above the tabs, so the composer owns the bottom of the screen. */}
					<Stack.Screen
						name="conversation/[threadId]"
						options={{ ...tabStackOptions, headerShown: true, headerLargeTitleEnabled: false }}
					/>
				</Stack>
			</WorkspaceProvider>
		);
	}

	return (
		<Screen testID="workspace-chooser">
			<QueryStates
				query={{ ...workspaces, isPending: workspaces.isPending || selected === undefined }}
				loadingLabel="Opening your workspace"
				errorTitle="Could not load your workspaces"
				empty={{
					when: (loaded) => loaded.length === 0,
					view: (
						<StateView
							state="empty"
							icon={{ ios: "person.3", android: "group" }}
							title="You are not in a workspace yet"
							message="Your account is ready. A workspace appears here once your team's Hephaestus admin adds you, or connects the organisation you belong to."
							action={{
								title: "Check again",
								onPress: () => {
									void workspaces.refetch();
								},
							}}
						/>
					),
				}}
			>
				{(loaded) =>
					selected === undefined ? (
						<StateView state="loading" label="Opening your workspace" />
					) : (
						<>
							<View style={{ gap: space.sm }}>
								<AppText variant="largeTitle" accessibilityRole="header">
									Choose a workspace
								</AppText>
								<AppText variant="callout" tone="secondaryLabel">
									You can switch later from your account.
								</AppText>
							</View>
							<WorkspaceList
								workspaces={loaded}
								onSelect={(workspace) => {
									void selectWorkspace(sessionKey, apiBaseUrl, workspace.workspaceSlug);
								}}
							/>
						</>
					)
				}
			</QueryStates>
			{/* Setting a workspace up is the web's: it knows who this Hephaestus lets create one, and walks an
			    admin through connecting GitHub or GitLab. Nothing here claims that everyone can. */}
			{workspaces.data?.length === 0 && instance !== undefined ? (
				<Section footer="Team admins set up a workspace on the web by connecting their GitHub organisation or GitLab group. Whether you can depends on how this Hephaestus is run.">
					<Row
						testID="workspace-set-up"
						title="Set up on the web"
						icon={{ ios: "safari", android: "open_in_browser" }}
						kind="action"
						onPress={() => {
							void WebBrowser.openBrowserAsync(`${webUrl(instance)}/workspaces/new`);
						}}
					/>
				</Section>
			) : null}
			{/* The account needs no workspace: someone who has none can still manage or delete it. */}
			<Section>
				<Row
					testID="account-open"
					title="Account"
					subtitle="Privacy, signed-in devices and notifications"
					icon={{ ios: "person.crop.circle", android: "account_circle" }}
					onPress={() => router.push("/account")}
				/>
			</Section>
			<Button
				title="Sign out"
				variant="secondary"
				onPress={() => {
					void signOut();
				}}
			/>
		</Screen>
	);
}
