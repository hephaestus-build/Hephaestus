import type { ReactNode } from "react";
import { StyleSheet, Text, type TextProps, useWindowDimensions } from "react-native";

import { type Palette, usePalette } from "./theme";

/**
 * The app's type ramp. Sizes follow the platform's text styles and scale with the person's text size
 * setting; hierarchy past the few sizes comes from weight and colour.
 */
const variants = StyleSheet.create({
	largeTitle: { fontSize: 34, fontWeight: "700" },
	title: { fontSize: 22, fontWeight: "700" },
	headline: { fontSize: 17, fontWeight: "600" },
	body: { fontSize: 17, lineHeight: 23 },
	callout: { fontSize: 16, lineHeight: 21 },
	subheadline: { fontSize: 15, lineHeight: 20 },
	footnote: { fontSize: 13, lineHeight: 18 },
	caption: { fontSize: 12, lineHeight: 16 },
});

/**
 * The iOS text style each variant is. iOS grows every style on its own curve — a large title far less
 * than body text at the largest sizes — and React Native otherwise scales them all like body text, so
 * titles would outgrow the screen. Android has one scale for all text and ignores this.
 */
const ramp = {
	largeTitle: "largeTitle",
	title: "title2",
	headline: "headline",
	body: "body",
	callout: "callout",
	subheadline: "subheadline",
	footnote: "footnote",
	caption: "caption1",
} as const satisfies Record<keyof typeof variants, TextProps["dynamicTypeRamp"]>;

type Tone =
	| "label"
	| "secondaryLabel"
	| "tertiaryLabel"
	| "accent"
	| "danger"
	| "success"
	| "onPrimary";

export interface AppTextProps extends TextProps {
	variant?: keyof typeof variants;
	tone?: Tone;
	weight?: "regular" | "semibold" | "bold";
	children: ReactNode;
}

const weights = { regular: "400", semibold: "600", bold: "700" } as const;

export function AppText({
	variant = "body",
	tone = "label",
	weight,
	style,
	...props
}: AppTextProps) {
	const palette: Palette = usePalette();
	// Changing the text size while the app runs keeps the old measurement of text already on screen,
	// cutting off the lines the larger size adds; a new text size is measured afresh.
	const { fontScale } = useWindowDimensions();
	return (
		<Text
			key={fontScale}
			dynamicTypeRamp={ramp[variant]}
			{...props}
			style={[
				variants[variant],
				{ color: palette[tone] },
				weight === undefined ? undefined : { fontWeight: weights[weight] },
				style,
			]}
		/>
	);
}
