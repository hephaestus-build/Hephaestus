import { QueryClientProvider } from "@tanstack/react-query";
import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import { useEffect } from "react";
import { useColorScheme } from "react-native";
import { KeyboardProvider } from "react-native-keyboard-controller";

import { usePushLifecycle } from "@/push/push-lifecycle";
import { activeQueryClient } from "@/query-client";
import { useSessionKey } from "@/session/authority";
import { restoreSession, useSession } from "@/session/session-store";
import { tabStackOptions } from "@/ui/navigation";

void SplashScreen.preventAutoHideAsync();

/** A report sits on the root stack, above the tabs, with a header of its own. */
const reportScreen = { ...tabStackOptions, headerShown: true, headerLargeTitleEnabled: false };

export default function RootLayout() {
	const session = useSession();
	// Read so the provider below receives the cache the authority handed this session.
	useSessionKey();
	const scheme = useColorScheme();

	useEffect(() => {
		void restoreSession();
	}, []);

	useEffect(() => {
		if (session.status !== "restoring") {
			void SplashScreen.hideAsync();
		}
	}, [session.status]);

	usePushLifecycle(session);

	// The navigator mounts from the first frame, as Expo Router needs; while the session is restored the
	// splash screen still covers it, and home waits for the answer (`index.tsx`).
	const signedIn = session.status === "signedIn";
	const consentRequired = signedIn && session.consent === "required";
	return (
		<QueryClientProvider client={activeQueryClient()}>
			<KeyboardProvider>
				<ThemeProvider value={scheme === "dark" ? DarkTheme : DefaultTheme}>
					<Stack screenOptions={{ headerShown: false }}>
						<Stack.Protected guard={!signedIn}>
							<Stack.Screen name="welcome" />
							<Stack.Screen
								name="instance"
								options={{ headerShown: true, headerBackButtonDisplayMode: "minimal" }}
							/>
							<Stack.Screen
								name="sign-in"
								options={{
									headerShown: true,
									title: "Sign in",
									headerBackButtonDisplayMode: "minimal",
								}}
							/>
						</Stack.Protected>
						<Stack.Protected guard={consentRequired}>
							<Stack.Screen name="consent" options={{ gestureEnabled: false }} />
						</Stack.Protected>
						<Stack.Protected guard={signedIn && !consentRequired}>
							<Stack.Screen name="(app)" />
						</Stack.Protected>
						{/* The account sheet, over whatever is open, with or without a workspace; its own layout keeps
						    all but privacy and deletion until the notice is accepted. */}
						<Stack.Protected guard={signedIn}>
							<Stack.Screen name="account" options={{ presentation: "modal" }} />
						</Stack.Protected>
						<Stack.Protected guard={signedIn && !consentRequired}>
							<Stack.Screen name="report" options={{ ...reportScreen, presentation: "modal" }} />
						</Stack.Protected>
					</Stack>
				</ThemeProvider>
			</KeyboardProvider>
		</QueryClientProvider>
	);
}
