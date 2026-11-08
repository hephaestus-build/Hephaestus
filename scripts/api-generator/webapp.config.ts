import { defineConfig } from "@hey-api/openapi-ts";
import { discriminatedUnionDates } from "./discriminated-union-dates.ts";

/**
 * A bundler keeps a module in one chunk. Each operation therefore has its own module, so a page
 * loads only the operations it calls. `sdk.gen.ts` and `@tanstack/react-query.gen.ts` stay as
 * re-exports. The operation modules import `createQueryKey` and `createInfiniteParams` from the
 * second one. An owned patch exports both (`patches/NOTICE.md`).
 */
const OPERATION_DIRECTORIES: Readonly<Record<string, string>> = {
	"@hey-api/sdk": "sdk",
	"@tanstack/react-query": "@tanstack/react-query",
};

/** The query key and infinite query helpers carry their operation only in their name. */
const NAMED_HELPER = /^(?<operation>.+?)(?:Infinite)?(?:QueryKey|Options)$/u;

function operationOf(symbol: { name: string; meta?: Readonly<Record<string, unknown>> }) {
	const { meta } = symbol;
	if (meta?.resource === "operation" && typeof meta.resourceId === "string") {
		return meta.resourceId;
	}
	return meta?.category === undefined
		? NAMED_HELPER.exec(symbol.name)?.groups?.operation
		: undefined;
}

function operationModule(symbol: { name: string; meta?: Readonly<Record<string, unknown>> }) {
	const directory = OPERATION_DIRECTORIES[String(symbol.meta?.plugin)];
	if (directory === undefined) {
		return;
	}
	const operation = operationOf(symbol);
	return operation === undefined ? undefined : { directory, file: `${directory}/${operation}` };
}

export default defineConfig({
	input: "../../server/openapi.yaml",
	output: "../../webapp/src/api",
	parser: {
		hooks: {
			symbols: {
				getFilePath: (symbol) => operationModule(symbol)?.file,
				getExportFromFilePath: (symbol) => {
					const module = operationModule(symbol);
					return module === undefined ? undefined : [module.directory];
				},
			},
		},
	},
	plugins: [
		"@hey-api/typescript",
		"@hey-api/schemas",
		"@hey-api/client-fetch",
		{
			name: "@tanstack/react-query",
			queryKeys: { tags: true },
		},
		{
			dates: true,
			bigInt: false,
			name: "@hey-api/transformers",
			transformers: [discriminatedUnionDates],
		},
		// Apply generated date transformers so SDK values match their Date types.
		{
			name: "@hey-api/sdk",
			transformer: true,
		},
		// Schemas for payloads that bypass the SDK, such as server-sent events. The resolvers make the
		// parsed output match the generated types: int64 stays a `number`, and dates become `Date`s as
		// the transformers above make them.
		{
			name: "zod",
			requests: false,
			responses: false,
			"~resolvers": {
				number: ({ $, schema, plugin }) =>
					schema.format === "int64" ? $(plugin.imports.z).attr("int").call() : undefined,
				string: ({ $, schema, plugin }) => {
					const iso =
						schema.format === "date-time"
							? $(plugin.imports.z)
									.attr("iso")
									.attr("datetime")
									.call($.object().prop("offset", $.literal(true)))
							: $(plugin.imports.z).attr("iso").attr("date").call();
					return schema.format === "date" || schema.format === "date-time"
						? iso.attr("pipe").call($(plugin.imports.z).attr("coerce").attr("date").call())
						: undefined;
				},
			},
		},
	],
});
