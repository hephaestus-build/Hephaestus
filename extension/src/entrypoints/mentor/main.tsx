import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { followSystemTheme } from "~/ui/theme";
import { createQueryClient } from "~/ui/worker-state";
import { MentorView } from "~/views/MentorView";

import "~/ui/styles.css";

// The Heph panel: Chrome's side panel for one tab, configured by the worker (`background/mentor.ts`).
const root = document.querySelector("#root");
if (root !== null) {
	document.documentElement.dataset.surface = "mentor";
	followSystemTheme();
	createRoot(root).render(
		<StrictMode>
			<QueryClientProvider client={createQueryClient()}>
				<MentorView />
			</QueryClientProvider>
		</StrictMode>,
	);
}
