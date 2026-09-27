import { ActivityIndicator, Pressable, StyleSheet } from "react-native";

import { AppText } from "./AppText";
import { radius, space, usePalette } from "./theme";

export interface ButtonProps {
	title: string;
	onPress: () => void;
	/** `plain` is text alone, for a quieter alternative beside a primary action. */
	variant?: "primary" | "secondary" | "destructive" | "plain";
	/** Shows a spinner in place of the title and ignores presses, without moving the layout. */
	pending?: boolean;
	disabled?: boolean;
	accessibilityHint?: string;
	testID?: string;
}

export function Button({
	title,
	onPress,
	variant = "primary",
	pending = false,
	disabled = false,
	accessibilityHint,
	testID,
}: ButtonProps) {
	const palette = usePalette();
	// One filled, label-coloured control commits; a grey one offers; blue text only ever links.
	const background = {
		primary: palette.primary,
		secondary: palette.fill,
		destructive: palette.fill,
		plain: "transparent",
	}[variant];
	const foreground = {
		primary: palette.onPrimary,
		secondary: palette.label,
		destructive: palette.danger,
		plain: palette.accent,
	}[variant];
	const inactive = disabled || pending;
	return (
		<Pressable
			testID={testID}
			onPress={onPress}
			disabled={inactive}
			accessibilityRole="button"
			accessibilityLabel={title}
			accessibilityHint={accessibilityHint}
			accessibilityState={{ disabled: inactive, busy: pending }}
			style={({ pressed }) => [
				styles.button,
				{ backgroundColor: background },
				pressed ? styles.pressed : undefined,
				disabled ? styles.disabled : undefined,
			]}
		>
			{pending ? (
				<ActivityIndicator color={foreground} />
			) : (
				<AppText
					variant={variant === "plain" ? "body" : "headline"}
					style={[styles.title, { color: foreground }]}
				>
					{title}
				</AppText>
			)}
		</Pressable>
	);
}

const styles = StyleSheet.create({
	button: {
		minHeight: 50,
		// Capsules, as the system draws prominent buttons.
		borderRadius: radius.pill,
		borderCurve: "continuous",
		alignItems: "center",
		justifyContent: "center",
		paddingHorizontal: space.lg,
	},
	title: { textAlign: "center" },
	pressed: { opacity: 0.75 },
	disabled: { opacity: 0.4 },
});
