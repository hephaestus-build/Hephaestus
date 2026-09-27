import { createContext, type ReactNode, useContext } from "react";
import { Platform, StyleSheet, View } from "react-native";

import { AppText } from "./AppText";
import { radius, space, usePalette } from "./theme";

export interface SectionProps {
	title?: string;
	/** Explains the section below its rows, like a grouped table's footer. */
	footer?: string;
	children: ReactNode;
	/**
	 * `grouped` (default) sets the rows on a rounded card, as settings do. `plain` sets them on the screen
	 * itself with no separators, under a stronger heading, for a spacious list such as conversations.
	 */
	appearance?: "grouped" | "plain";
}

const Appearance = createContext<"grouped" | "plain">("grouped");

/** The appearance of the section a row sits in. */
export function useSectionStyle(): "grouped" | "plain" {
	return useContext(Appearance);
}

/**
 * An inset grouped section: a caption, rows on a card, an optional footer. Its items are `Row`s or
 * `SectionItem`s, which draw the separators between them; anything else draws none.
 */
export function Section({ title, footer, children, appearance = "grouped" }: SectionProps) {
	const palette = usePalette();
	const plain = appearance === "plain";
	return (
		<View style={styles.section}>
			{title === undefined ? null : (
				// Sentence case, as written: a heading reads as words, never as shouted capitals.
				<AppText
					variant={plain ? "headline" : "subheadline"}
					tone={plain ? "label" : "secondaryLabel"}
					weight="semibold"
					accessibilityRole="header"
					style={plain ? undefined : styles.caption}
				>
					{title}
				</AppText>
			)}
			<Appearance.Provider value={appearance}>
				{plain ? (
					<View>{children}</View>
				) : (
					<View style={[styles.card, { backgroundColor: palette.card }]}>
						{/* Every item draws a separator on its top edge; lifting the items by one hairline inside the
						    clipped card hides the first one, so separators appear only between items. */}
						<View style={styles.items}>{children}</View>
					</View>
				)}
			</Appearance.Provider>
			{footer === undefined ? null : (
				<AppText
					variant="footnote"
					tone="secondaryLabel"
					style={plain ? undefined : styles.caption}
				>
					{footer}
				</AppText>
			)}
		</View>
	);
}

/**
 * The hairline on top of a grouped section's item, hidden by the section on its first item; a plain
 * section separates its rows with space alone. iOS starts it where
 * the item's text starts and stops short of the trailing edge, as its grouped lists do; Android runs it
 * edge to edge.
 */
export function Separator({ inset }: { inset: number }) {
	const palette = usePalette();
	if (useSectionStyle() === "plain") {
		return null;
	}
	return (
		<View
			pointerEvents="none"
			style={[
				styles.separator,
				Platform.OS === "ios" ? { left: inset, right: space.lg } : undefined,
				{ backgroundColor: palette.separator },
			]}
		/>
	);
}

/** A section's row surface, for content that is not a `Row` (a toggle, a paragraph). */
export function SectionItem({ children }: { children: ReactNode }) {
	const palette = usePalette();
	return (
		<View style={{ backgroundColor: palette.card }}>
			{children}
			<Separator inset={space.lg} />
		</View>
	);
}

/** Free content on a section's card — a paragraph, a quote — padded as a row's text is. */
export function SectionText({ children }: { children: ReactNode }) {
	return (
		<SectionItem>
			<View style={styles.text}>{children}</View>
		</SectionItem>
	);
}

const styles = StyleSheet.create({
	section: { gap: space.sm },
	text: { padding: space.lg, gap: space.sm },
	caption: { paddingHorizontal: space.lg },
	card: { borderRadius: radius.card, borderCurve: "continuous", overflow: "hidden" },
	items: { marginTop: -StyleSheet.hairlineWidth },
	separator: {
		position: "absolute",
		top: 0,
		left: 0,
		right: 0,
		height: StyleSheet.hairlineWidth,
	},
});
