import Constants from "expo-constants";
import * as Device from "expo-device";
import { Platform } from "react-native";

import { defaultInstanceAddress } from "./instance/default-instance";

/**
 * Which build is running, read from its scheme (`app.config.ts`). Only the development build may talk
 * to a plain-HTTP local server or offer the dev sign-in; the others refuse both.
 */
export const IS_DEVELOPMENT_BUILD = Constants.expoConfig?.scheme === "build.hephaestus.app.dev";

const extra: unknown = Constants.expoConfig?.extra;
const configured =
	typeof extra === "object" &&
	extra !== null &&
	"defaultInstance" in extra &&
	typeof extra.defaultInstance === "string"
		? extra.defaultInstance
		: undefined;
const port =
	typeof extra === "object" &&
	extra !== null &&
	"devServerPort" in extra &&
	typeof extra.devServerPort === "number"
		? extra.devServerPort
		: undefined;

/** The Hephaestus the welcome screen offers first; `instance/default-instance.ts` says which. */
export const DEFAULT_INSTANCE: string | undefined = defaultInstanceAddress({
	development: IS_DEVELOPMENT_BUILD,
	configured,
	override: process.env.EXPO_PUBLIC_DEV_SERVER_URL,
	devServerPort: port,
	isDevice: Device.isDevice,
	platform: Platform.OS === "android" ? "android" : "ios",
	metroHost: Constants.expoConfig?.hostUri?.split(":")[0],
});
