import { type ColorValue, Platform, PlatformColor } from "react-native";

import type { GroupVisual } from "./group-visual";

/** A group's catalog colour on this platform: iOS's system colour, which follows dark mode itself. */
export function groupColor(tone: GroupVisual["tone"]): ColorValue {
	return Platform.OS === "ios" ? PlatformColor(tone.ios) : tone.android;
}
