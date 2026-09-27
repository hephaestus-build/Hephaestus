import { NativeTabs } from "expo-router/unstable-native-tabs";

import hephTabSelected from "@assets/heph-tab-selected.png";
import hephTab from "@assets/heph-tab.png";

import { usePalette } from "@/ui/theme";

/**
 * Two destinations: where the developer stands in their practices, and Heph to talk it through. The
 * account opens from the avatar on each, not a tab of its own. Material asks for three to five
 * destinations in a navigation bar; Android keeps these two rather than invent a third, and the
 * trade-off is recorded in docs/contributor/mobile.mdx § Tabs.
 */
export default function TabsLayout() {
	const palette = usePalette();
	return (
		<NativeTabs
			tintColor={palette.accent}
			minimizeBehavior="onScrollDown"
			labelVisibilityMode="labeled"
		>
			<NativeTabs.Trigger name="practice">
				<NativeTabs.Trigger.Label>Practice</NativeTabs.Trigger.Label>
				{/* A clipboard of practices, filled as tab symbols are, not a chart: the tab shows standings
				    in words and draws no charts. */}
				<NativeTabs.Trigger.Icon
					sf={{ default: "list.bullet.clipboard.fill", selected: "list.bullet.clipboard.fill" }}
					md="checklist"
				/>
			</NativeTabs.Trigger>
			{/* Heph is the mentor, not a chat feature: its tab shows its own face, tinted like the system
			    icon beside it. The prominent tab role is not available from a released library yet — see
			    docs/contributor/mobile.mdx § Tabs. */}
			<NativeTabs.Trigger name="heph">
				<NativeTabs.Trigger.Label>Heph</NativeTabs.Trigger.Label>
				<NativeTabs.Trigger.Icon
					src={{ default: hephTab, selected: hephTabSelected }}
					renderingMode="template"
				/>
			</NativeTabs.Trigger>
		</NativeTabs>
	);
}
