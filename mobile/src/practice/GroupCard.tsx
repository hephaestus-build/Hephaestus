import { Pressable, StyleSheet, View } from "react-native";

import type { WorkspaceListItem } from "@/api/types.gen";
import { AppText } from "@/ui/AppText";
import { Icon } from "@/ui/Icon";
import { radius, space, usePalette } from "@/ui/theme";

import { groupVisual } from "./group-visual";
import { basedOn, type GroupEntry, tally, tallyText } from "./profile";
import { GroupGlyph, StandingLabel } from "./Standing";
import { STANDING, TREND } from "./vocabulary";

export interface GroupCardProps {
	entry: GroupEntry;
	provider: WorkspaceListItem["providerType"];
	onPress: () => void;
}

/**
 * One practice group with evidence: where the developer stands, which way it has been moving when that
 * can be said, how its practices stand, and the reviewed work it rests on. The whole card opens the
 * group; its parts are read as one sentence.
 */
export function GroupCard({ entry, provider, onPress }: GroupCardProps) {
	const palette = usePalette();
	const direction = entry.verdict?.direction;
	const trend =
		direction === undefined || direction === "INSUFFICIENT_EVIDENCE"
			? undefined
			: TREND[direction].label;
	// How the practices stand, unless every one stands where the group does, which the label says already.
	const parts = tally(entry.practices);
	const practices =
		parts.length === 1 && parts[0]?.standing === entry.standing
			? ""
			: `Practices: ${tallyText(entry.practices)}`;
	const evidence = basedOn(entry.verdict?.sources, provider);
	return (
		<Pressable
			testID={`group-${entry.group.slug}`}
			onPress={onPress}
			accessibilityRole="button"
			accessibilityLabel={[
				entry.group.name,
				STANDING[entry.standing].label,
				trend,
				practices === "" ? undefined : practices,
				evidence,
			]
				.filter((part) => part !== undefined)
				.join(", ")}
			accessibilityHint="Opens the group"
			style={({ pressed }) => [
				styles.card,
				{ backgroundColor: pressed ? palette.fill : palette.card },
			]}
		>
			<View style={styles.title}>
				<GroupGlyph visual={groupVisual(entry.group.icon, entry.group.color)} size={22} />
				<AppText variant="headline" style={styles.name}>
					{entry.group.name}
				</AppText>
				<Icon
					name={{ ios: "chevron.right", android: "chevron_right" }}
					size={14}
					weight="semibold"
					tintColor={palette.tertiaryLabel}
				/>
			</View>
			<View style={styles.standing}>
				<StandingLabel standing={entry.standing} />
				{trend === undefined ? null : (
					<AppText variant="subheadline" tone="secondaryLabel">
						{trend}
					</AppText>
				)}
			</View>
			{practices === "" ? null : (
				<AppText variant="subheadline" tone="secondaryLabel">
					{practices}
				</AppText>
			)}
			{evidence === undefined ? null : (
				<AppText variant="footnote" tone="secondaryLabel">
					{evidence}
				</AppText>
			)}
		</Pressable>
	);
}

const styles = StyleSheet.create({
	card: { padding: space.lg, gap: space.sm, borderRadius: radius.card, borderCurve: "continuous" },
	title: { flexDirection: "row", alignItems: "center", gap: space.md },
	name: { flex: 1 },
	standing: { gap: space.xs },
});
