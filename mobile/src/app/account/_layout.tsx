import { Stack, useRouter } from "expo-router";
import { Platform, Pressable, StyleSheet } from "react-native";

import { closeSheet } from "@/account/close-sheet";
import { useSession } from "@/session/session-store";
import { Icon } from "@/ui/Icon";
import { tabStackOptions } from "@/ui/navigation";
import { usePalette } from "@/ui/theme";

/**
 * The account sheet: one stack presented over the app from the avatar on each tab, from the
 * workspace chooser and from the consent notice. Privacy and deleting the account are reachable before
 * the notice is accepted; everything else waits for it, as the server does.
 *
 * Every setting in it takes effect as it is changed, so there is nothing to confirm or cancel: the
 * sheet has the system's Close button, not Done, which Apple reserves for a sheet that completes a task
 * or saves changes. It sits at the trailing edge on every page, since the leading edge is Back once a
 * page is pushed, and closes the whole sheet from however deep in it the person is.
 */
export default function AccountLayout() {
	const router = useRouter();
	const palette = usePalette();
	const session = useSession();
	const consentRequired = session.status === "signedIn" && session.consent === "required";
	return (
		<Stack
			screenOptions={({ navigation }) => {
				const close = () => closeSheet(navigation.getParent(), () => router.replace("/"));
				return {
					...tabStackOptions,
					headerLargeTitleEnabled: false,
					...(Platform.OS === "ios"
						? {
								unstable_headerRightItems: () => [
									{
										type: "button",
										label: "Close",
										icon: { type: "sfSymbol", name: "xmark" },
										accessibilityLabel: "Close",
										onPress: close,
									},
								],
							}
						: {
								headerRight: () => (
									<Pressable
										testID="account-close"
										accessibilityRole="button"
										accessibilityLabel="Close"
										onPress={close}
										style={styles.close}
									>
										<Icon
											name={{ ios: "xmark", android: "close" }}
											size={24}
											tintColor={palette.label}
										/>
									</Pressable>
								),
							}),
				};
			}}
		>
			<Stack.Protected guard={!consentRequired}>
				{/* A compact title: the sheet opens on the person's own picture and name. */}
				<Stack.Screen name="index" />
				<Stack.Screen name="workspaces" />
				<Stack.Screen name="sessions" />
				<Stack.Screen name="notifications" />
				<Stack.Screen name="about" />
			</Stack.Protected>
			<Stack.Screen name="privacy" />
		</Stack>
	);
}

const styles = StyleSheet.create({
	// The smallest target a finger reliably hits.
	close: { minWidth: 48, minHeight: 48, alignItems: "center", justifyContent: "center" },
});
