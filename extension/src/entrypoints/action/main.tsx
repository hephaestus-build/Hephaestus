import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { followSystemTheme } from "~/ui/theme";
import { createQueryClient } from "~/ui/worker-state";
import { ActionView } from "~/views/ActionView";

import "~/ui/styles.css";

const root = document.querySelector("#root");
// Only ever a top-level window the worker opened: the page is not web-accessible, and a framed copy
// renders nothing at all.
if (root !== null && window.top === window) {
	followSystemTheme();
	createRoot(root).render(
		<StrictMode>
			<QueryClientProvider client={createQueryClient()}>
				<ActionView />
			</QueryClientProvider>
		</StrictMode>,
	);
}
