import { ActivityIndicator, StyleSheet, View } from "react-native";

import { AppText } from "./AppText";
import { Button } from "./Button";
import { Icon, type IconName } from "./Icon";
import { space, usePalette } from "./theme";

/**
 * What a screen shows when it has nothing to show yet: still loading, genuinely empty, or failed.
 * Each says which, in words; a spinner never stands in for an answer the app already has.
 */
export type StateViewProps =
	| { state: "loading"; label: string }
	| {
			state: "empty";
			icon: IconName;
			title: string;
			message: string;
			action?: StateAction;
	  }
	| { state: "error"; title: string; message: string; onRetry?: () => void };

interface StateAction {
	title: string;
	onPress: () => void;
}

export function StateView(props: StateViewProps) {
	const palette = usePalette();
	if (props.state === "loading") {
		return (
			<View
				style={styles.center}
				accessible
				accessibilityRole="progressbar"
				accessibilityLabel={props.label}
			>
				<ActivityIndicator />
				<AppText variant="subheadline" tone="secondaryLabel">
					{props.label}
				</AppText>
			</View>
		);
	}
	if (props.state === "empty") {
		return (
			<View style={styles.center}>
				<Icon name={props.icon} size={40} tintColor={palette.tertiaryLabel} />
				<AppText variant="headline" style={styles.centered} accessibilityRole="header">
					{props.title}
				</AppText>
				<AppText variant="subheadline" tone="secondaryLabel" style={styles.centered}>
					{props.message}
				</AppText>
				{props.action === undefined ? null : (
					<Button title={props.action.title} onPress={props.action.onPress} variant="secondary" />
				)}
			</View>
		);
	}
	return (
		<View style={styles.center} accessibilityLiveRegion="polite">
			<Icon
				name={{ ios: "exclamationmark.triangle", android: "warning" }}
				size={36}
				tintColor={palette.warning}
			/>
			<AppText variant="headline" style={styles.centered} accessibilityRole="header">
				{props.title}
			</AppText>
			<AppText variant="subheadline" tone="secondaryLabel" style={styles.centered}>
				{props.message}
			</AppText>
			{props.onRetry === undefined ? null : (
				<Button title="Try again" onPress={props.onRetry} variant="secondary" />
			)}
		</View>
	);
}

const styles = StyleSheet.create({
	center: {
		alignItems: "center",
		gap: space.md,
		paddingVertical: space.xl * 2,
		paddingHorizontal: space.lg,
	},
	// Stretched to the column so a long line wraps: centred by the column alone, text is measured at
	// its unwrapped width and runs off the screen at large text sizes.
	centered: { textAlign: "center", alignSelf: "stretch" },
});
