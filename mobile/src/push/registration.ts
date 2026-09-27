import Constants from "expo-constants";
import * as Device from "expo-device";
import * as Notifications from "expo-notifications";
import { Platform } from "react-native";

import { getPushDeviceStatus, registerPushDevice, unregisterPushDevice } from "@/api/sdk.gen";
import { currentSessionKey } from "@/session/authority";
import { installationId } from "@/session/session-store";

import { reconcileRegistration } from "./reconcile";
import { createSerialQueue } from "./serial";
import { registerForSession } from "./session-owned";

/** Every registration change for this installation, one at a time: `serial.ts` says why. */
const serialized = createSerialQueue();

/**
 * Push reaches an installation only through the Expo project that published this build. A build made
 * without one — every local build until the maintainers provision EAS — has no project id and cannot
 * receive push, which the app says rather than pretending.
 */
export function pushProjectId(): string | undefined {
	const extra: unknown = Constants.expoConfig?.extra;
	if (typeof extra === "object" && extra !== null && "eas" in extra) {
		const eas: unknown = extra.eas;
		if (
			typeof eas === "object" &&
			eas !== null &&
			"projectId" in eas &&
			typeof eas.projectId === "string"
		) {
			return eas.projectId;
		}
	}
	return undefined;
}

export type PushCapability = "ready" | "no-project" | "no-device";

export function pushCapability(): PushCapability {
	if (pushProjectId() === undefined) {
		return "no-project";
	}
	return Device.isDevice ? "ready" : "no-device";
}

/**
 * Registers this installation with the server for the session `owner` names, the current one unless
 * said otherwise; true when it now receives push.
 */
export async function registerThisDevice(owner: string = currentSessionKey()): Promise<boolean> {
	return serialized(async () => register(owner));
}

async function register(owner: string): Promise<boolean> {
	const outcome = await registerForSession({
		stillCurrent: () => currentSessionKey() === owner,
		projectId: pushProjectId(),
		permitted: async () => {
			const permission = await Notifications.getPermissionsAsync();
			return permission.granted;
		},
		pushToken: async (projectId) => {
			const token = await Notifications.getExpoPushTokenAsync({ projectId });
			return token.data;
		},
		send: async (pushToken) => {
			await registerPushDevice({
				path: { installationId: installationId() },
				body: { expoPushToken: pushToken, platform: Platform.OS === "ios" ? "IOS" : "ANDROID" },
				throwOnError: true,
			});
		},
	});
	return outcome === "done";
}

/** Unregisters for the session `owner` names, the current one unless said otherwise — checked when the queue reaches it. */
export async function unregisterThisDevice(owner: string = currentSessionKey()): Promise<void> {
	await serialized(async () => {
		if (currentSessionKey() === owner) {
			await unregister();
		}
	});
}

async function unregister(): Promise<void> {
	await unregisterPushDevice({ path: { installationId: installationId() }, throwOnError: true });
}

/**
 * Keeps a registration the person chose true to this device — see `reconcile.ts` — for the session
 * `owner` names. Only a build that can receive push asks the server at all.
 */
export async function reconcileThisDevice(owner: string, tokenChanged: boolean): Promise<void> {
	if (pushCapability() !== "ready") {
		return;
	}
	await serialized(async () => reconcile(owner, tokenChanged));
}

async function reconcile(owner: string, tokenChanged: boolean): Promise<void> {
	if (currentSessionKey() !== owner) {
		return;
	}
	const [status, permission] = await Promise.all([
		getPushDeviceStatus({ path: { installationId: installationId() }, throwOnError: true }),
		Notifications.getPermissionsAsync(),
	]);
	if (currentSessionKey() !== owner) {
		return;
	}
	const action = reconcileRegistration({
		registered: status.data.registered,
		permitted: permission.granted,
		tokenChanged,
	});
	if (action === "unregister") {
		await unregister();
	} else if (action === "refresh") {
		await register(owner);
	}
}
