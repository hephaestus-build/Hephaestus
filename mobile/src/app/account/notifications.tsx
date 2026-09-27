import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as Notifications from "expo-notifications";
import { Stack, useFocusEffect } from "expo-router";
import { useCallback } from "react";
import { Linking, StyleSheet, Switch, View } from "react-native";

import {
	getPushDeviceStatusOptions,
	getPushDeviceStatusQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { pushCapability, registerThisDevice, unregisterThisDevice } from "@/push/registration";
import { installationId } from "@/session/session-store";
import { AppText } from "@/ui/AppText";
import { InlineError } from "@/ui/InlineError";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { space, usePalette } from "@/ui/theme";

const ABOUT =
	"A notification only says that new practice feedback is waiting, never what it says, because it can show on a locked screen. The feedback itself opens in the app.";

export default function NotificationSettings() {
	const palette = usePalette();
	const queryClient = useQueryClient();
	const path = { installationId: installationId() };
	const status = useQuery(getPushDeviceStatusOptions({ path }));
	const permission = useQuery({
		// oxlint-disable-next-line hephaestus/no-manual-query-key -- device permission has no HTTP contract or generated query key
		queryKey: ["notification-permission"],
		queryFn: async () => Notifications.getPermissionsAsync(),
	});
	const capability = pushCapability();
	const refetchPermission = permission.refetch;
	useFocusEffect(
		useCallback(() => {
			void refetchPermission();
		}, [refetchPermission]),
	);
	const refresh = () => {
		void queryClient.invalidateQueries({ queryKey: getPushDeviceStatusQueryKey({ path }) });
	};
	const enable = useMutation({
		mutationFn: async () => {
			const asked = await Notifications.requestPermissionsAsync();
			void permission.refetch();
			if (!asked.granted) {
				return false;
			}
			return registerThisDevice();
		},
		onSettled: refresh,
	});
	const disable = useMutation({
		mutationFn: async () => unregisterThisDevice(),
		onSettled: refresh,
	});

	let body;
	if (status.isError) {
		body = (
			<StateView
				state="error"
				title="Could not check notifications"
				message="Check your connection and try again."
				onRetry={() => {
					void status.refetch();
				}}
			/>
		);
	} else if (status.isPending || permission.isPending) {
		body = <StateView state="loading" label="Checking notifications" />;
	} else if (!status.data.available) {
		body = (
			<StateView
				state="empty"
				icon={{ ios: "bell.slash", android: "notifications_off" }}
				title="This Hephaestus does not send notifications"
				message="Its administrator has not set up push notifications. New feedback still appears under Practice feedback."
			/>
		);
	} else if (capability === "no-project") {
		body = (
			<StateView
				state="empty"
				icon={{ ios: "bell.slash", android: "notifications_off" }}
				title="This build cannot receive notifications"
				message="Only the app from the App Store or Google Play can. New feedback still appears under Practice feedback."
			/>
		);
	} else if (capability === "no-device") {
		body = (
			<StateView
				state="empty"
				icon={{ ios: "bell.slash", android: "notifications_off" }}
				title="Notifications need a real device"
				message="A simulator cannot receive push notifications."
			/>
		);
	} else if (permission.data?.granted !== true && permission.data?.canAskAgain === false) {
		body = (
			<StateView
				state="empty"
				icon={{ ios: "bell.slash", android: "notifications_off" }}
				title="Notifications are off in Settings"
				message="Allow notifications for Hephaestus in your device settings, then come back here."
				action={{
					title: "Open Settings",
					onPress: () => {
						void Linking.openSettings();
					},
				}}
			/>
		);
	} else {
		const on = status.data.registered && permission.data?.granted === true;
		body = (
			<Section footer={ABOUT}>
				<View style={[styles.toggle, { backgroundColor: palette.card }]}>
					<AppText style={styles.label} nativeID="push-label">
						New practice feedback
					</AppText>
					<Switch
						testID="push-toggle"
						value={on}
						disabled={enable.isPending || disable.isPending}
						accessibilityLabelledBy="push-label"
						accessibilityLabel="Notify me about new practice feedback"
						onValueChange={(next) => (next ? enable.mutate() : disable.mutate())}
					/>
				</View>
			</Section>
		);
	}

	return (
		<>
			<Stack.Screen options={{ title: "Notifications", headerLargeTitleEnabled: false }} />
			<Screen testID="notifications-screen">
				{body}
				{enable.isError ? (
					<InlineError
						testID="push-enable-error"
						message="Notifications were not turned on: this phone could not be registered."
						retrying={enable.isPending}
						onRetry={() => enable.mutate()}
					/>
				) : null}
				{disable.isError ? (
					<InlineError
						testID="push-disable-error"
						message="Notifications are still on: this phone could not be unregistered."
						retrying={disable.isPending}
						onRetry={() => disable.mutate()}
					/>
				) : null}
			</Screen>
		</>
	);
}

const styles = StyleSheet.create({
	toggle: { flexDirection: "row", alignItems: "center", gap: space.md, padding: space.lg },
	label: { flex: 1 },
});
