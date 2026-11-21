import Foundation
import HephaestusAPI
import Testing

struct MissingFixture: Error {
  let name: String
}

/// Decodes a fixture from scripts/client-generators/fixtures, as the generated client decodes a response body.
func decodeFixture<T: Decodable>(_ name: String, as type: T.Type) throws -> T {
  guard let url = Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures") else {
    throw MissingFixture(name: name)
  }
  let decoder = JSONDecoder()
  decoder.dateDecodingStrategy = .iso8601
  return try decoder.decode(type, from: Data(contentsOf: url))
}

@Test func ignoresAnUnknownResponseProperty() throws {
  let workspace = try decodeFixture("workspace-list-item", as: Components.Schemas.WorkspaceListItem.self)
  #expect(workspace.workspaceSlug == "octo-org")
  #expect(workspace.status == "ACTIVE")
}

@Test func keepsAValueOfAnOpenEnumThatItDoesNotKnow() throws {
  let workspace = try decodeFixture("workspace-list-item-unknown-status", as: Components.Schemas.WorkspaceListItem.self)
  #expect(workspace.status == "PAUSED_IN_A_LATER_RELEASE")
}

@Test func keepsAProblemTypeThatItDoesNotKnow() throws {
  let problem = try decodeFixture("problem-unknown-type", as: Components.Schemas.ProblemDetail.self)
  #expect(problem._type == "https://example.com/problems/added-in-a-later-release")
  #expect(problem.status == 409)
  #expect(problem.title == "Added in a later release")
}
