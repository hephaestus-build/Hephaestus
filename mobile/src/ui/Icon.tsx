import { SymbolView, type SymbolViewProps } from "expo-symbols";
import {
	type ColorValue,
	Platform,
	type StyleProp,
	useWindowDimensions,
	View,
	type ViewStyle,
} from "react-native";

export type IconName = SymbolViewProps["name"];

export interface IconProps {
	name: IconName;
	size: number;
	tintColor: ColorValue;
	weight?: SymbolViewProps["weight"];
	style?: StyleProp<ViewStyle>;
	/**
	 * Grows with the person's text size, as a system list's symbols do beside their text. Off for a
	 * symbol in a control of fixed size, such as a toolbar or the composer's buttons.
	 */
	withText?: boolean;
}

/** Beyond this, a symbol beside text stops growing: the text alone carries the largest sizes. */
const MAX_SYMBOL_SCALE = 1.6;

/**
 * The size a symbol is drawn at, and the box it takes, at the person's text size — for a layout that
 * lines something up with the symbol, such as a row's separator.
 */
export function useSymbolSize(size: number, withText: boolean): { drawn: number; box: number } {
	const { fontScale } = useWindowDimensions();
	// iOS draws the symbol at the size it is given, so one beside text is given the text's scale.
	const drawn =
		Platform.OS === "ios" && withText ? size * Math.min(fontScale, MAX_SYMBOL_SCALE) : size;
	return { drawn, box: Platform.OS === "android" ? size * fontScale : drawn };
}

/**
 * A symbol beside text that already says what it means, so screen readers skip it.
 *
 * iOS draws an SF Symbol natively at `size`. On Android, expo-symbols draws a Material Symbol as a font
 * glyph, which the system text size scales, inside a box of `size`: at large text sizes the glyph
 * outgrows the box and is cut off. The box grows by the same scale, so the icon keeps pace with the text
 * next to it and stays whole.
 */
export function Icon({ name, size, tintColor, weight, style, withText = false }: IconProps) {
	const { drawn, box } = useSymbolSize(size, withText);
	return (
		<View
			style={style}
			accessible={false}
			importantForAccessibility="no-hide-descendants"
			accessibilityElementsHidden
		>
			<SymbolView
				name={name}
				size={drawn}
				tintColor={tintColor}
				weight={weight}
				style={{ width: box, height: box }}
			/>
		</View>
	);
}
