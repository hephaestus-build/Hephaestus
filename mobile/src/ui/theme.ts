import {
	type ColorValue,
	DynamicColorIOS,
	Platform,
	PlatformColor,
	useColorScheme,
} from "react-native";

/**
 * Colours by role. iOS reads the system's semantic colours, which follow light, dark and increased
 * contrast without a re-render; Android has no equivalent set below its dynamic-colour API, so it
 * picks from two palettes by the current scheme.
 */
export interface Palette {
	background: ColorValue;
	groupedBackground: ColorValue;
	card: ColorValue;
	label: ColorValue;
	secondaryLabel: ColorValue;
	tertiaryLabel: ColorValue;
	separator: ColorValue;
	fill: ColorValue;
	/** The brand's signal blue: the tab tint, links, action rows and Heph's mark. */
	accent: ColorValue;
	/**
	 * The one filled, committing control on a screen (send, the main button, a chosen option): the label
	 * colour itself, black in light and white in dark, so it never competes with Heph's blue mark.
	 */
	primary: ColorValue;
	onPrimary: ColorValue;
	danger: ColorValue;
	success: ColorValue;
	warning: ColorValue;
}

/** Built lazily: `DynamicColorIOS` and `PlatformColor` names are only meaningful on iOS. */
const ios = (): Palette => ({
	background: PlatformColor("systemBackground"),
	groupedBackground: PlatformColor("systemGroupedBackground"),
	card: PlatformColor("secondarySystemGroupedBackground"),
	label: PlatformColor("label"),
	secondaryLabel: PlatformColor("secondaryLabel"),
	tertiaryLabel: PlatformColor("tertiaryLabel"),
	separator: PlatformColor("separator"),
	fill: PlatformColor("tertiarySystemFill"),
	// The product's signal blue, lifted in dark mode exactly as the web theme's `brand-accent` does.
	accent: DynamicColorIOS({ light: "#315FDC", dark: "#8EAEFF" }),
	primary: PlatformColor("label"),
	onPrimary: PlatformColor("systemBackground"),
	danger: PlatformColor("systemRed"),
	success: PlatformColor("systemGreen"),
	warning: PlatformColor("systemOrange"),
});

const iosPalette = Platform.OS === "ios" ? ios() : undefined;

const androidLight: Palette = {
	background: "#FFFFFF",
	groupedBackground: "#F4F5F9",
	card: "#FFFFFF",
	label: "#17191F",
	secondaryLabel: "#596174",
	tertiaryLabel: "#687084",
	separator: "#DDE1EA",
	fill: "#ECEFF5",
	accent: "#315FDC",
	primary: "#17191F",
	onPrimary: "#FFFFFF",
	danger: "#C62828",
	success: "#2E7D32",
	warning: "#9A5A00",
};

const androidDark: Palette = {
	background: "#0B0D12",
	groupedBackground: "#0B0D12",
	card: "#171A22",
	label: "#F8FAFC",
	secondaryLabel: "#A9B1C3",
	tertiaryLabel: "#8D95A8",
	separator: "#2A2F3B",
	fill: "#232834",
	accent: "#8EAEFF",
	primary: "#F8FAFC",
	onPrimary: "#0B0D12",
	danger: "#FF8A80",
	success: "#81C995",
	warning: "#FFB74D",
};

export function usePalette(): Palette {
	const scheme = useColorScheme();
	if (iosPalette !== undefined) {
		return iosPalette;
	}
	return scheme === "dark" ? androidDark : androidLight;
}

/** Spacing steps; everything between elements is a `gap`, everything inside is `padding`. */
export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24 } as const;

/** iOS rounds grouped lists and cards more than Material does. */
export const radius = { card: Platform.OS === "ios" ? 26 : 14, control: 10, pill: 999 } as const;
