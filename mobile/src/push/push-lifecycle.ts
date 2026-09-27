import * as Notifications from "expo-notifications";
import { router } from "expo-router";
import { useEffect } from "react";
import { AppState, Platform } from "react-native";

import { currentSessionKey, useSessionKey } from "@/session/authority";
import { onBeforeSignOut, session as authority, type SessionState } from "@/session/session-store";
import { selectWorkspace } from "@/workspace/workspace-store";

import { parsePushPayload } from "./payload";
import { reconcileThisDevice, unregisterThisDevice } from "./registration";
import { openForSession } from "./session-owned";

// A nudge that arrives while the app is open is still shown; it never plays a sound or sets a badge,
// because the app cannot know how much is unread without reading it.
Notifications.setNotificationHandler({
	handleNotification: async () => ({
		shouldShowBanner: true,
		shouldShowList: true,
		shouldPlaySound: false,
		shouldSetBadge: false,
	}),
});

/** The Android channel the server names; people can silence it in system settings on its own. */
const CHANNEL_ID = "practice-feedback";

if (Platform.OS === "android") {
	void Notifications.setNotificationChannelAsync(CHANNEL_ID, {
		name: "Practice feedback",
		description: "When new practice feedback is waiting for you",
		importance: Notifications.AndroidImportance.DEFAULT,
	});
}

/**
 * Opening a notification switches to its workspace and shows practice feedback, which reads it while
 * the person is looking at it. Nothing is fetched on the notification's behalf before that.
 */
async function openFromNotification(
	response: Notifications.NotificationResponse,
	apiBaseUrl: string,
): Promise<void> {
	const payload = parsePushPayload(response.notification.request.content.data);
	if (payload === undefined) {
		return;
	}
	const owner = currentSessionKey();
	await openForSession({
		addressedTo: payload.nativeSessionId,
		currentSignIn: () => {
			const state = authority.getState();
			return state.status === "signedIn" ? state.nativeSessionId : undefined;
		},
		stillCurrent: () => currentSessionKey() === owner,
		selectWorkspace: async () => selectWorkspace(owner, apiBaseUrl, payload.workspaceSlug),
		showFeedback: () => router.navigate("/practice/feedback"),
	});
}

/** Best effort: the notification settings screen shows the state when the person looks. */
async function reconcileQuietly(owner: string, tokenChanged: boolean): Promise<void> {
	try {
		await reconcileThisDevice(owner, tokenChanged);
	} catch {
		// Offline, or the server is unreachable: the next return to the foreground tries again.
	}
}

async function unregisterQuietly(): Promise<void> {
	try {
		await unregisterThisDevice();
	} catch {
		// The session ends next; its end makes the registration ineligible on the server anyway.
	}
}

export function usePushLifecycle(session: SessionState): void {
	const sessionKey = useSessionKey();
	const signedInTo =
		session.status === "signedIn" && session.consent !== "required"
			? session.instance.apiBaseUrl
			: undefined;
	const lastResponse = Notifications.useLastNotificationResponse();

	// A device is registered only when the person turns notifications on. While signed in, each return
	// to the foreground and each new push token checks that registration still holds: permission
	// revoked in the system settings unregisters it; a new token is registered in the old one's place.
	useEffect(() => {
		if (signedInTo === undefined) {
			return;
		}
		void reconcileQuietly(sessionKey, false);
		const foreground = AppState.addEventListener("change", (state) => {
			if (state === "active") {
				void reconcileQuietly(sessionKey, false);
			}
		});
		const token = Notifications.addPushTokenListener(() => {
			void reconcileQuietly(sessionKey, true);
		});
		return () => {
			foreground.remove();
			token.remove();
		};
	}, [signedInTo, sessionKey]);

	useEffect(() => onBeforeSignOut(unregisterQuietly), []);

	useEffect(() => {
		if (signedInTo !== undefined && lastResponse !== null && lastResponse !== undefined) {
			void openFromNotification(lastResponse, signedInTo);
			Notifications.clearLastNotificationResponse();
		}
	}, [lastResponse, signedInTo]);
}
