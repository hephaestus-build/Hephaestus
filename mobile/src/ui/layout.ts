import { useWindowDimensions } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { space } from "./theme";

/**
 * The widest content grows on a wide screen — an iPad, a phone on its side — where a full-width line
 * is hard to read and a full-width button hard to take in. `reading` is close to UIKit's readable
 * content width; `actions` suits a short stack of buttons.
 */
export const COLUMN = { reading: 672, actions: 440 } as const;

/**
 * Horizontal padding for a screen's content: the margin, measured from the safe area so nothing sits
 * under the camera housing or a rounded corner when the phone is on its side, then centred in a column
 * no wider than `maxWidth`. A vertical scroll view does not inset its content for the side safe area,
 * so every full-width screen takes its side padding from here. On a phone held upright it is just the
 * margin.
 */
export function useColumnPadding(
	margin: number = space.lg,
	maxWidth: number = COLUMN.reading,
): { paddingLeft: number; paddingRight: number } {
	const { width } = useWindowDimensions();
	const { left, right } = useSafeAreaInsets();
	const start = left + margin;
	const end = right + margin;
	const spare = Math.max(0, width - start - end - maxWidth) / 2;
	return { paddingLeft: start + spare, paddingRight: end + spare };
}

/**
 * From this text scale on, text is at an accessibility size, where content beside it no longer fits and
 * stacks under it instead. React Native reports iOS's largest standard size (xxxL) as 1.353 and its first
 * accessibility size (AX1) as 1.786 (`RCTAccessibilityManager`); Android's largest scales pass it too.
 */
export const STACKED_TEXT_SCALE = 1.5;

export function useStackedText(): boolean {
	return useWindowDimensions().fontScale >= STACKED_TEXT_SCALE;
}
