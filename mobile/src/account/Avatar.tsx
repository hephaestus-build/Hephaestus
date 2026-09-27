import { useState } from "react";
import { Image, StyleSheet, View } from "react-native";

import { Icon } from "@/ui/Icon";
import { usePalette } from "@/ui/theme";

/**
 * The account's picture. While the account loads, an empty circle holds its place; once loaded, an
 * account without a picture, or one whose picture fails to load, shows the system's person symbol.
 * Decorative: whatever shows it says whose account it is.
 */
export function Avatar({
	loading,
	url,
	size,
}: {
	loading: boolean;
	url: string | undefined;
	size: number;
}) {
	const palette = usePalette();
	const [failed, setFailed] = useState<string | undefined>();
	const circle = { width: size, height: size, borderRadius: size / 2 };
	if (loading) {
		return <View style={[circle, { backgroundColor: palette.fill }]} />;
	}
	if (url === undefined || failed === url) {
		return (
			<Icon
				name={{ ios: "person.crop.circle.fill", android: "account_circle" }}
				size={size}
				tintColor={palette.tertiaryLabel}
			/>
		);
	}
	return (
		<Image
			source={{ uri: url }}
			style={[circle, styles.image, { borderColor: palette.separator }]}
			onError={() => setFailed(url)}
			accessibilityIgnoresInvertColors
			accessible={false}
		/>
	);
}

const styles = StyleSheet.create({
	image: { borderWidth: StyleSheet.hairlineWidth },
});
