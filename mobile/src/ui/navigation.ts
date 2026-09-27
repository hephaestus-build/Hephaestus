import type { Stack } from "expo-router";
import type { ComponentProps } from "react";
import { Platform } from "react-native";

type StackOptions = NonNullable<ComponentProps<typeof Stack>["screenOptions"]>;

/**
 * Each tab's stack: a large title on iOS that collapses into the bar as the screen scrolls, with
 * content running under the translucent bar; Android keeps its standard top app bar.
 */
export const tabStackOptions = {
	headerLargeTitleEnabled: true,
	headerTransparent: Platform.OS === "ios",
	headerLargeTitleShadowVisible: false,
	headerShadowVisible: Platform.OS !== "ios",
	headerBackButtonDisplayMode: "minimal",
} satisfies StackOptions;
