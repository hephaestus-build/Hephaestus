import { StyleSheet, View } from "react-native";

import { AppText } from "./AppText";
import { radius, space, usePalette } from "./theme";

export interface PillProps {
	label: string;
	tone?: "neutral" | "accent" | "success" | "warning";
}

/**
 * A short status word next to a title, like a practice standing. The word carries the meaning, in the
 * label colour so it stays legible at any size; the role colour only marks it, as a dot and outline.
 */
export function Pill({ label, tone = "neutral" }: PillProps) {
	const palette = usePalette();
	const color = {
		neutral: palette.secondaryLabel,
		accent: palette.accent,
		success: palette.success,
		warning: palette.warning,
	}[tone];
	return (
		<View style={[styles.pill, { borderColor: color }]}>
			<View style={[styles.dot, { backgroundColor: color }]} />
			<AppText variant="footnote" weight="semibold">
				{label}
			</AppText>
		</View>
	);
}

const styles = StyleSheet.create({
	pill: {
		alignSelf: "flex-start",
		flexDirection: "row",
		alignItems: "center",
		gap: space.xs + 2,
		borderWidth: StyleSheet.hairlineWidth * 2,
		borderRadius: radius.pill,
		paddingHorizontal: space.sm,
		paddingVertical: 3,
	},
	dot: { width: 7, height: 7, borderRadius: 4 },
});
