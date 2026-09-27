import { Platform, Pressable, StyleSheet, View } from "react-native";

import { AppText } from "./AppText";
import { Icon, type IconName, useSymbolSize } from "./Icon";
import { useStackedText } from "./layout";
import { Separator, useSectionStyle } from "./Section";
import { space, usePalette } from "./theme";

export interface RowProps {
	title: string;
	subtitle?: string;
	/** A value shown at the trailing edge, like a setting's current choice. */
	value?: string;
	/** A line icon before the title, in the title's own colour: quiet, never a coloured tile. */
	icon?: IconName;
	onPress?: () => void;
	/** `navigate` shows a chevron; `destructive` colours the title. */
	kind?: "navigate" | "action" | "destructive" | "static";
	/**
	 * Marks the row as one choice among its section's rows: iOS shows a checkmark at the trailing edge of
	 * the chosen one, and the title keeps the label colour.
	 */
	checked?: boolean;
	disabled?: boolean;
	accessibilityHint?: string;
	testID?: string;
}

/** One row of a grouped section: the unit every list and setting in the app is built from. */
export function Row({
	title,
	subtitle,
	value,
	icon,
	onPress,
	kind,
	checked,
	disabled = false,
	accessibilityHint,
	testID,
}: RowProps) {
	const palette = usePalette();
	const plain = useSectionStyle() === "plain";
	// At accessibility text sizes the value moves under the title, whole, rather than squeezing it.
	const largeText = useStackedText();
	const { box: iconBox } = useSymbolSize(ICON, true);
	const stacked = value !== undefined && largeText;
	const shape =
		checked === undefined ? (kind ?? (onPress === undefined ? "static" : "navigate")) : "static";
	const tone = {
		navigate: "label",
		static: "label",
		action: "accent",
		destructive: "danger",
	} as const;
	const content = (
		<>
			{icon === undefined ? null : (
				<Icon name={icon} size={ICON} tintColor={palette[tone[shape]]} withText />
			)}
			<View style={styles.text}>
				<AppText tone={tone[shape]}>{title}</AppText>
				{subtitle === undefined ? null : (
					<AppText variant="subheadline" tone="secondaryLabel">
						{subtitle}
					</AppText>
				)}
				{stacked ? <AppText tone="secondaryLabel">{value}</AppText> : null}
			</View>
			{value === undefined || stacked ? null : (
				<AppText tone="secondaryLabel" numberOfLines={1} style={styles.value}>
					{value}
				</AppText>
			)}
			{checked === true ? (
				<Icon
					name={{ ios: "checkmark", android: "check" }}
					size={17}
					weight="semibold"
					tintColor={palette.accent}
				/>
			) : null}
			{shape === "navigate" ? (
				<Icon
					name={{ ios: "chevron.right", android: "chevron_right" }}
					size={14}
					weight="semibold"
					tintColor={palette.tertiaryLabel}
				/>
			) : null}
			<Separator inset={icon === undefined ? space.lg : space.lg + iconBox + space.md} />
		</>
	);
	if (onPress === undefined) {
		return (
			<View
				style={[styles.row, plain ? styles.plain : { backgroundColor: palette.card }]}
				testID={testID}
				accessible
				accessibilityLabel={[title, subtitle, value].filter(Boolean).join(", ")}
			>
				{content}
			</View>
		);
	}
	return (
		<Pressable
			testID={testID}
			onPress={onPress}
			disabled={disabled}
			accessibilityRole="button"
			accessibilityLabel={[title, subtitle, value].filter(Boolean).join(", ")}
			accessibilityHint={accessibilityHint}
			// A choice row says whether it is the chosen one; other rows carry no selection state.
			accessibilityState={checked === undefined ? { disabled } : { disabled, selected: checked }}
			android_ripple={{ color: palette.fill }}
			style={({ pressed }) => [
				styles.row,
				plain
					? [styles.plain, pressed ? styles.plainPressed : undefined]
					: { backgroundColor: pressed ? palette.fill : palette.card },
				disabled ? styles.disabled : undefined,
			]}
		>
			{content}
		</Pressable>
	);
}

const ICON = 22;

const styles = StyleSheet.create({
	row: {
		// The height of a row in the system's own grouped lists.
		minHeight: Platform.OS === "ios" ? 52 : 48,
		flexDirection: "row",
		alignItems: "center",
		gap: space.md,
		paddingHorizontal: space.lg,
		paddingVertical: space.md,
	},
	// A plain list's rows sit on the screen itself, aligned with its headings, and dim when pressed.
	plain: { paddingHorizontal: 0, backgroundColor: "transparent" },
	plainPressed: { opacity: 0.5 },
	text: { flex: 1, gap: 2 },
	value: { maxWidth: "45%" },
	disabled: { opacity: 0.45 },
});
