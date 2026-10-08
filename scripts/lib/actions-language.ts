import type * as Expressions from "@actions/expressions";
import type * as Service from "@actions/languageservice";
import type * as Parser from "@actions/workflow-parser";
import type * as ActionParser from "@actions/workflow-parser/actions/action-parser";
import type * as ActionTemplate from "@actions/workflow-parser/actions/action-template";
import type * as TemplateContext from "@actions/workflow-parser/templates/template-context";
import type * as WorkflowSchema from "@actions/workflow-parser/workflows/workflow-schema";
import type * as YamlReader from "@actions/workflow-parser/workflows/yaml-object-reader";

import { createServer, isRunnableDevEnvironment } from "vite-plus";

/** GitHub's workflow packages import JSON without import attributes, which native Node refuses. */
const OFFICIAL = ["@actions/languageservice", "@actions/workflow-parser", "@actions/expressions"];

export interface ActionsLanguage {
	service: typeof Service;
	parser: typeof Parser;
	actionParser: typeof ActionParser;
	actionTemplate: typeof ActionTemplate;
	templateContext: typeof TemplateContext;
	workflowSchema: typeof WorkflowSchema;
	yamlReader: typeof YamlReader;
	expressions: typeof Expressions;
	close: () => Promise<void>;
}

/**
 * Loads GitHub's workflow language packages through Vite's SSR module runner. Every module comes
 * from one graph, so the classes the validator checks with `instanceof` are the ones passed in.
 */
export async function openActionsLanguage(): Promise<ActionsLanguage> {
	const server = await createServer({
		configFile: false,
		logLevel: "silent",
		appType: "custom",
		server: { middlewareMode: true, watch: null, ws: false },
		ssr: { noExternal: OFFICIAL },
	});
	try {
		const { ssr } = server.environments;
		if (!isRunnableDevEnvironment(ssr)) {
			throw new Error("Vite's SSR environment cannot run modules");
		}
		const load = async <T>(specifier: string) => ssr.runner.import<T>(specifier);
		return {
			service: await load<ActionsLanguage["service"]>("@actions/languageservice"),
			parser: await load<ActionsLanguage["parser"]>("@actions/workflow-parser"),
			actionParser: await load<ActionsLanguage["actionParser"]>(
				"@actions/workflow-parser/actions/action-parser",
			),
			actionTemplate: await load<ActionsLanguage["actionTemplate"]>(
				"@actions/workflow-parser/actions/action-template",
			),
			templateContext: await load<ActionsLanguage["templateContext"]>(
				"@actions/workflow-parser/templates/template-context",
			),
			workflowSchema: await load<ActionsLanguage["workflowSchema"]>(
				"@actions/workflow-parser/workflows/workflow-schema",
			),
			yamlReader: await load<ActionsLanguage["yamlReader"]>(
				"@actions/workflow-parser/workflows/yaml-object-reader",
			),
			expressions: await load<ActionsLanguage["expressions"]>("@actions/expressions"),
			close: async () => server.close(),
		};
	} catch (error) {
		await server.close();
		throw error;
	}
}
