import { StyleSheet, View } from "react-native";

import { AppText } from "@/ui/AppText";
import { Icon } from "@/ui/Icon";
import { space, usePalette } from "@/ui/theme";

/**
 * Which workspace a tab shows, at the top of it, for someone in more than one: the context every list
 * below is read in. With only one, there is nothing to tell apart, and it stays out of the way.
 */
export function WorkspaceLabel({ name }: { name: string }) {
	const palette = usePalette();
	return (
		<View style={styles.label} accessible accessibilityLabel={`Workspace: ${name}`}>
			<Icon
				name={{ ios: "square.stack.3d.up", android: "workspaces" }}
				size={15}
				tintColor={palette.secondaryLabel}
			/>
			<AppText variant="subheadline" weight="semibold" tone="secondaryLabel" style={styles.name}>
				{name}
			</AppText>
		</View>
	);
}

const styles = StyleSheet.create({
	label: { flexDirection: "row", alignItems: "center", gap: space.sm },
	name: { flexShrink: 1 },
});
