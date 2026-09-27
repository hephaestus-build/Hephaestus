import { StyleSheet, View } from "react-native";

import { AppText } from "@/ui/AppText";
import { Icon, type IconName } from "@/ui/Icon";
import { space, usePalette } from "@/ui/theme";

import { groupColor } from "./group-color";
import type { GroupVisual } from "./group-visual";
import { STANDING, type Standing } from "./vocabulary";

/**
 * Each standing has its own shape, so it is told apart without its colour: a warning for what needs
 * attention, half a circle for mixed, a check for going well, a slash for nothing to report and a
 * dashed ring for not observed.
 */
export const STANDING_GLYPH: Record<Standing, IconName> = {
	DEVELOPING: { ios: "exclamationmark.circle.fill", android: "error" },
	MIXED: { ios: "circle.lefthalf.filled", android: "contrast" },
	STRENGTH: { ios: "checkmark.circle.fill", android: "check_circle" },
	NO_OPPORTUNITY: { ios: "circle.slash", android: "block" },
	NOT_OBSERVED: { ios: "circle.dashed", android: "radio_button_unchecked" },
};

function useStandingColor(standing: Standing) {
	const palette = usePalette();
	return {
		warning: palette.warning,
		success: palette.success,
		accent: palette.accent,
		neutral: standing === "MIXED" ? palette.secondaryLabel : palette.tertiaryLabel,
	}[STANDING[standing].tone];
}

/** A standing in words beside its glyph: the full label under a title, the short one in a list. */
export function StandingLabel({
	standing,
	length = "full",
	variant = "subheadline",
}: {
	standing: Standing;
	length?: "full" | "short";
	variant?: "subheadline" | "headline" | "footnote";
}) {
	const color = useStandingColor(standing);
	const def = STANDING[standing];
	return (
		<View style={styles.label}>
			<Icon
				name={STANDING_GLYPH[standing]}
				size={variant === "footnote" ? 13 : 17}
				tintColor={color}
				withText
			/>
			<AppText variant={variant} weight="semibold" style={styles.text}>
				{length === "full" ? def.label : def.shortLabel}
			</AppText>
		</View>
	);
}

/** A practice group's own glyph, in its catalog colour. */
export function GroupGlyph({ visual, size }: { visual: GroupVisual; size: number }) {
	return <Icon name={visual.icon} size={size} tintColor={groupColor(visual.tone)} />;
}

const styles = StyleSheet.create({
	label: { flexDirection: "row", alignItems: "center", gap: space.xs + 2 },
	text: { flexShrink: 1 },
});
