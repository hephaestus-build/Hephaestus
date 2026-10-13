import { defineConfig } from "@hey-api/openapi-ts";

export default defineConfig({
	input: "../../server/openapi.yaml",
	output: "../../webapp/src/api",
	plugins: [
		"@hey-api/typescript",
		"@hey-api/client-fetch",
		{
			name: "@tanstack/react-query",
			queryKeys: { tags: true },
		},
		{
			dates: true,
			bigInt: false,
			name: "@hey-api/transformers",
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
