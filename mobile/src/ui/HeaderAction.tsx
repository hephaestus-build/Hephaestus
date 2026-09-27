import { Stack } from "expo-router";
import type { ComponentProps } from "react";
import { Platform, Pressable } from "react-native";

import { AppText } from "./AppText";

type SymbolName = Extract<ComponentProps<typeof Stack.Toolbar.Button>["icon"], string>;

export interface HeaderActionProps {
	/** The SF Symbol iOS shows; Android's app bar takes only bitmap icons, so it shows `label`. */
	symbol: SymbolName;
	label: string;
	onPress: () => void;
}

/** One action at the trailing end of the screen's header, visible on both platforms. */
export function HeaderAction({ symbol, label, onPress }: HeaderActionProps) {
	if (Platform.OS === "ios") {
		return (
			<Stack.Toolbar placement="right">
				<Stack.Toolbar.Button icon={symbol} accessibilityLabel={label} onPress={onPress} />
			</Stack.Toolbar>
		);
	}
	return (
		<Stack.Screen
			options={{
				headerRight: () => (
					<Pressable accessibilityRole="button" onPress={onPress} hitSlop={12}>
						<AppText tone="accent" weight="semibold">
							{label}
						</AppText>
					</Pressable>
				),
			}}
		/>
	);
}
