import type { Decorator } from "@storybook/react-vite";

import { SidebarProvider } from "@/components/ui/sidebar";

/**
 * Frames one sidebar section the way the real sidebar does — its surface, edge and inset — so a
 * section's story is judged against the background it ships on. The frame is a plain box around the
 * provider, because the provider is a layout root and does not own a look.
 */
export const withSidebarFrame: Decorator = (Story) => (
	<div className="w-64 rounded-lg border border-border bg-sidebar p-2">
		<SidebarProvider className="min-h-0">
			<Story />
		</SidebarProvider>
	</div>
);
