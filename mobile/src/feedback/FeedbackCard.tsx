import { Pressable, StyleSheet, View } from "react-native";

import type { InAppFeedback } from "@/api/types.gen";
import { AppText } from "@/ui/AppText";
import { formatRelative } from "@/ui/dates";
import { radius, space, usePalette } from "@/ui/theme";

import { seenOn } from "./work-kind";

export interface FeedbackCardProps {
	feedback: InAppFeedback;
	provider: "GITHUB" | "GITLAB" | "SLACK" | "OUTLINE" | undefined;
	onPress: () => void;
}

/** One piece of practice feedback in its list: the habit, the practice, how often it was seen. */
export function FeedbackCard({ feedback, provider, onPress }: FeedbackCardProps) {
	const palette = usePalette();
	const seen = seenOn(
		feedback.evidence.map((item) => item.reviewedWork),
		provider,
	);
	return (
		<Pressable
			testID={`feedback-${feedback.id}`}
			onPress={onPress}
			accessibilityRole="button"
			accessibilityLabel={[feedback.practiceName, feedback.headline, seen]
				.filter((part) => part !== "")
				.join(", ")}
			accessibilityHint="Opens the feedback"
			style={({ pressed }) => [
				styles.card,
				{ backgroundColor: palette.card },
				pressed ? { opacity: 0.8 } : undefined,
			]}
		>
			<View style={styles.meta}>
				<AppText
					variant="footnote"
					tone="accent"
					weight="semibold"
					style={styles.practice}
					numberOfLines={1}
				>
					{feedback.practiceName}
				</AppText>
			</View>
			<AppText variant="headline">{feedback.headline}</AppText>
			<AppText variant="footnote" tone="secondaryLabel">
				{[seen, formatRelative(feedback.preparedAt)].filter((part) => part !== "").join(" · ")}
			</AppText>
		</Pressable>
	);
}

const styles = StyleSheet.create({
	card: { padding: space.lg, gap: space.sm, borderRadius: radius.card, borderCurve: "continuous" },
	meta: { flexDirection: "row", alignItems: "center", gap: space.sm },
	practice: { flexShrink: 1 },
});
