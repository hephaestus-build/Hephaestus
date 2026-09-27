import { Stack } from "expo-router";
import type { ComponentProps } from "react";
import { Platform, Pressable, StyleSheet, View } from "react-native";

import { AppText } from "@/ui/AppText";

import { Avatar } from "./Avatar";

/** Large enough to recognise a face at a glance, as the Health app sets it beside its title. */
const SIZE = 40;

type SymbolName = Extract<ComponentProps<typeof Stack.Toolbar.Button>["icon"], string>;

export interface AccountButtonProps {
	avatarUrl: string | undefined;
	loading: boolean;
	onPress: () => void;
	/** A tab's own action, shown before the picture in its own group, such as a new conversation. */
	action?: { symbol: SymbolName; label: string; onPress: () => void; testID?: string };
}

/**
 * The account, at the trailing edge of a tab's header: the person's picture from their code host, which
 * opens the account sheet. It is on every tab, so the account is never a tab of its own. On iOS it is a
 * native toolbar item with the shared glass background hidden, so the picture itself is the control;
 * a tab's action sits before it, set apart as its own item.
 */
export function AccountButton({ action, avatarUrl, loading, onPress }: AccountButtonProps) {
	const avatar = (
		<Pressable
			testID="account-button"
			accessibilityRole="button"
			accessibilityLabel="Account"
			accessibilityHint="Opens your account, workspace and settings"
			hitSlop={4}
			onPress={onPress}
			style={({ pressed }) => [styles.target, pressed ? styles.pressed : undefined]}
		>
			<Avatar loading={loading} url={avatarUrl} size={SIZE} />
		</Pressable>
	);
	if (Platform.OS === "ios") {
		return (
			<Stack.Toolbar placement="right">
				{action === undefined ? null : (
					<Stack.Toolbar.Button
						icon={action.symbol}
						accessibilityLabel={action.label}
						onPress={action.onPress}
					/>
				)}
				{action === undefined ? null : <Stack.Toolbar.Spacer width={8} />}
				<Stack.Toolbar.View hidesSharedBackground>{avatar}</Stack.Toolbar.View>
			</Stack.Toolbar>
		);
	}
	// Android's app bar takes only bitmap icons, so a tab's action there is its label.
	return (
		<Stack.Screen
			options={{
				headerRight: () => (
					<View style={styles.row}>
						{action === undefined ? null : (
							<Pressable
								testID={action.testID}
								accessibilityRole="button"
								onPress={action.onPress}
								style={styles.target}
							>
								<AppText tone="accent" weight="semibold">
									{action.label}
								</AppText>
							</Pressable>
						)}
						{avatar}
					</View>
				),
			}}
		/>
	);
}

const styles = StyleSheet.create({
	// The smallest target a finger reliably hits, around a smaller picture.
	target: { minWidth: 44, minHeight: 44, alignItems: "center", justifyContent: "center" },
	pressed: { opacity: 0.6 },
	row: { flexDirection: "row", alignItems: "center", gap: 8 },
});
