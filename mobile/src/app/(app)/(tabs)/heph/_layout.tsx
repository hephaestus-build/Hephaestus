import { Stack } from "expo-router";

import { tabStackOptions } from "@/ui/navigation";

export default function TabStack() {
	return <Stack screenOptions={tabStackOptions} />;
}
