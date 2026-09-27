import { Image } from "react-native";

import mark from "@assets/splash-icon.png";

/**
 * Heph's face: the Hephaestus mark, the same glyph the web app shows as Heph's avatar. It appears only
 * where Heph speaks, beside its name, so screen readers skip it.
 */
export function HephMark({ size }: { size: number }) {
	return (
		<Image
			source={mark}
			style={{ width: size, height: size }}
			accessible={false}
			accessibilityElementsHidden
			importantForAccessibility="no-hide-descendants"
			accessibilityIgnoresInvertColors
		/>
	);
}
