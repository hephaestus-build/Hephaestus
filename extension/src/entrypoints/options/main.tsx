import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { followSystemTheme } from "~/ui/theme";
import { createQueryClient } from "~/ui/worker-state";
import { OptionsView } from "~/views/OptionsView";

import "~/ui/styles.css";

const root = document.querySelector("#root");
if (root !== null) {
	followSystemTheme();
	createRoot(root).render(
		<StrictMode>
			<QueryClientProvider client={createQueryClient()}>
				<OptionsView />
			</QueryClientProvider>
		</StrictMode>,
	);
}
