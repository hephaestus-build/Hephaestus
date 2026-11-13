// swift-tools-version:6.0
// The Swift client of `vp run gate:client-generators`. The gate copies server/openapi.yaml into
// Sources/HephaestusAPI and the fixtures into Tests/HephaestusAPITests/Fixtures, then runs `swift test`.
import PackageDescription

let package = Package(
  name: "HephaestusAPI",
  platforms: [.macOS(.v13)],
  dependencies: [
    .package(url: "https://github.com/apple/swift-openapi-generator", exact: "1.14.4"),
    .package(url: "https://github.com/apple/swift-openapi-runtime", exact: "1.13.4"),
  ],
  targets: [
    .target(
      name: "HephaestusAPI",
      dependencies: [.product(name: "OpenAPIRuntime", package: "swift-openapi-runtime")],
      plugins: [.plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator")]
    ),
    .testTarget(
      name: "HephaestusAPITests",
      dependencies: ["HephaestusAPI"],
      resources: [.copy("Fixtures")]
    ),
  ]
)
