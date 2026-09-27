import type { ReactNode } from "react";
import { StyleSheet, View } from "react-native";

import { AppText } from "@/ui/AppText";
import { HephMark } from "@/ui/HephMark";
import { space } from "@/ui/theme";

export interface HephSaysProps {
	/** What Heph says, from `mentor-voice.ts`. */
	children: string;
	/** What the person can do next, under the words. */
	action?: ReactNode;
	testID?: string;
}

/**
 * Heph speaking on a mentor surface: its face and name, then its words, read as one. Not a message —
 * nothing here was sent, stored or written by the AI service — so it has no bubble and no time.
 */
export function HephSays({ children, action, testID }: HephSaysProps) {
	return (
		<View style={styles.note} testID={testID}>
			<View accessible accessibilityLabel={`Heph: ${children}`} style={styles.words}>
				<View style={styles.signature}>
					<HephMark size={28} />
					<AppText variant="subheadline" weight="semibold" tone="secondaryLabel">
						Heph
					</AppText>
				</View>
				<AppText>{children}</AppText>
			</View>
			{action}
		</View>
	);
}

const styles = StyleSheet.create({
	note: { gap: space.md },
	words: { gap: space.sm },
	signature: { flexDirection: "row", alignItems: "center", gap: space.sm },
});
