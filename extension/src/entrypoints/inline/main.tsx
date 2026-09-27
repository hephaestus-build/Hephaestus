import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import {
	frameProviderSchema,
	frameThemeSchema,
	PROVIDER_PARAMETER,
	THEME_PARAMETER,
} from "~/shared/frame-messages";
import { followSystemTheme } from "~/ui/theme";
import { createQueryClient } from "~/ui/worker-state";
import { InlineView } from "~/views/InlineView";

import "~/ui/styles.css";

const root = document.querySelector("#root");
if (root !== null) {
	// The provider's look and theme, from the address the page opened this frame with, before the
	// first paint. Anything unrecognised falls back to the neutral look and the system's theme.
	const parameters = new URLSearchParams(location.search);
	const provider = frameProviderSchema.safeParse(parameters.get(PROVIDER_PARAMETER));
	const theme = frameThemeSchema.safeParse(parameters.get(THEME_PARAMETER));
	document.documentElement.dataset.surface = "inline";
	if (provider.success) {
		document.documentElement.dataset.provider = provider.data;
	}
	followSystemTheme(theme.success ? theme.data : "system");
	createRoot(root).render(
		<StrictMode>
			<QueryClientProvider client={createQueryClient()}>
				<InlineView />
			</QueryClientProvider>
		</StrictMode>,
	);
}
