import assert from "node:assert/strict";
import { test } from "node:test";

import { stringify } from "yaml";

import { changedPostgresql } from "./chart-postgresql.ts";

const statefulSet = (container: object, metadata: object = {}) =>
	stringify({
		apiVersion: "apps/v1",
		kind: "StatefulSet",
		metadata: { name: "heph-postgresql", labels: { "app.kubernetes.io/component": "postgresql" } },
		spec: {
			template: {
				metadata: { labels: { "app.kubernetes.io/component": "postgresql" }, ...metadata },
				spec: {
					containers: [
						{
							name: "postgresql",
							image: "postgres@sha256:aa",
							args: ["-c", "ssl=on"],
							...container,
						},
					],
				},
			},
		},
	});
const service = stringify({
	apiVersion: "v1",
	kind: "Service",
	metadata: { name: "heph-postgresql" },
});
const chart = (...documents: string[]) => documents.join("---\n");
const env = (...names: string[]) => names.map((name) => ({ name, value: name }));

void test("the same template keeps PostgreSQL", () => {
	assert.deepEqual(
		changedPostgresql(chart(service, statefulSet({})), chart(statefulSet({}), service)),
		[],
	);
});

void test("a new image or new arguments restart PostgreSQL once, as the release intends", () => {
	assert.deepEqual(
		changedPostgresql(
			chart(statefulSet({})),
			chart(
				statefulSet({
					image: "postgres@sha256:bb",
					args: ["-c", "ssl=on", "-c", "max_connections=240"],
				}),
			),
		),
		[".spec.containers[postgresql].image", ".spec.containers[postgresql].args"],
	);
});

const memory = (request: string, limit: string) => ({
	resources: { requests: { memory: request }, limits: { memory: limit } },
});

void test("a new memory request or limit restarts PostgreSQL once, because the chart derives it from the settings", () => {
	assert.deepEqual(
		changedPostgresql(
			chart(statefulSet(memory("2Gi", "2Gi"))),
			chart(statefulSet(memory("3Gi", "3Gi"))),
		),
		[
			".spec.containers[postgresql].resources.requests.memory",
			".spec.containers[postgresql].resources.limits.memory",
		],
	);
});

void test("a memory change together with any other template change still fails", () => {
	assert.throws(
		() =>
			changedPostgresql(
				chart(statefulSet(memory("2Gi", "2Gi"))),
				chart(statefulSet({ ...memory("3Gi", "3Gi"), env: env("A") })),
			),
		/\.spec\.containers\[postgresql\]\.env/u,
	);
	assert.throws(
		() =>
			changedPostgresql(
				chart(statefulSet({ resources: { requests: { memory: "2Gi", cpu: "1" } } })),
				chart(statefulSet({ resources: { requests: { memory: "3Gi", cpu: "2" } } })),
			),
		/resources\.requests\.cpu/u,
	);
});

void test("any other change to the Pod template fails, because it would cut the database on every upgrade", () => {
	assert.throws(
		() =>
			changedPostgresql(
				chart(statefulSet({})),
				chart(statefulSet({}, { annotations: { version: "1.0.0" } })),
			),
		/beyond its image, arguments and memory: \.metadata\.annotations/u,
	);
	assert.throws(
		() => changedPostgresql(chart(service), chart(service)),
		/no PostgreSQL StatefulSet/u,
	);
});

void test("an added or renamed container, an env change or a reordered env fails, even beside a new image", () => {
	const image = { image: "postgres@sha256:bb" };
	for (const [next, path] of [
		[
			chart(statefulSet(image).replace("name: postgresql", "name: database")),
			/\.spec\.containers/u,
		],
		[chart(statefulSet({ ...image, env: env("A") })), /\.spec\.containers\[postgresql\]\.env/u],
	] as const) {
		assert.throws(() => changedPostgresql(chart(statefulSet({})), next), path);
	}
	assert.throws(
		() =>
			changedPostgresql(
				chart(statefulSet({ env: env("A", "B") })),
				chart(statefulSet({ ...image, env: env("B", "A") })),
			),
		/\.spec\.containers\[postgresql\]\.env/u,
	);
	assert.throws(
		() =>
			changedPostgresql(
				chart(statefulSet({ env: env("A", "A") })),
				chart(
					statefulSet({
						...image,
						env: [
							{ name: "A", value: "A" },
							{ name: "A", value: "B" },
						],
					}),
				),
			),
		/\.spec\.containers\[postgresql\]\.env/u,
	);
});
