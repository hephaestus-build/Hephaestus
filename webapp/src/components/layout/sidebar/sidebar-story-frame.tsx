import type { Decorator } from "@storybook/react-vite";

import { SidebarProvider } from "@/components/ui/sidebar";

/** Puts a sidebar section on the sidebar's own surface, so its story is judged against the background it ships on. */
export const withSidebarFrame: Decorator = (Story) => (
	<div className="w-64 rounded-lg border border-border bg-sidebar p-2">
		<SidebarProvider className="min-h-0">
			<Story />
		</SidebarProvider>
	</div>
);
