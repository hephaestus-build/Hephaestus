import type { ReactNode } from "react";
import { RefreshControl, StyleSheet } from "react-native";
import { KeyboardAwareScrollView } from "react-native-keyboard-controller";

import { useColumnPadding } from "./layout";
import { space, usePalette } from "./theme";

export interface ScreenProps {
	children: ReactNode;
	/** Pull to refresh; shows the system spinner while `refreshing`. */
	onRefresh?: () => void;
	refreshing?: boolean;
	/** `plain` sits on the system background, for a spacious list rather than grouped settings. */
	surface?: "grouped" | "plain";
	testID?: string;
}

/**
 * A scrolling screen under a native header. iOS insets it for the large title and tab bar itself
 * (`contentInsetAdjustmentBehavior`); the navigators own those edges on Android. A focused field
 * scrolls clear of the keyboard, and a button pressed while the keyboard is up acts on the first tap
 * rather than only closing the keyboard.
 */
export function Screen({
	children,
	onRefresh,
	refreshing = false,
	surface = "grouped",
	testID,
}: ScreenProps) {
	const palette = usePalette();
	const column = useColumnPadding();
	return (
		<KeyboardAwareScrollView
			testID={testID}
			bottomOffset={space.xl}
			keyboardShouldPersistTaps="handled"
			contentInsetAdjustmentBehavior="automatic"
			style={{
				backgroundColor: surface === "plain" ? palette.background : palette.groupedBackground,
			}}
			contentContainerStyle={[styles.content, column]}
			keyboardDismissMode="interactive"
			refreshControl={
				onRefresh === undefined ? undefined : (
					<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
				)
			}
		>
			{children}
		</KeyboardAwareScrollView>
	);
}

const styles = StyleSheet.create({
	content: { paddingTop: space.lg, gap: space.xl, paddingBottom: space.xl * 2 },
});
