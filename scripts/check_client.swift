import Foundation
@main struct CheckClient {
    @MainActor static func main() async throws {
        let domain = "etabli-client-test-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: domain)!
        defer { defaults.removePersistentDomain(forName: domain) }
        let client = PBClient(session: fixtureSession(), defaults: defaults)
        let server = URL(string: "https://fixture.invalid/paperless")!
        FixtureProtocol.replies = [(200, #"{"token":"secret"}"#)]
        try await client.connect(baseURL: server, username: "user", password: "password")
        precondition(FixtureProtocol.requests.last!.url!.absoluteString == "https://fixture.invalid/paperless/api/token/")
        precondition(Keychain.values["paperbox.paperless:token"] == nil)
        FixtureProtocol.replies = [(200, #"{"count":1,"results":[{"id":42,"title":"Invoice","archive_serial_number":123}]}"#)]
        let documents = try await client.listDocuments(query: "budget & cost")
        precondition(documents.first?.archive_serial_number == 123)
        let request = FixtureProtocol.requests.last!
        precondition(request.value(forHTTPHeaderField: "Authorization") == "Token secret")
        precondition(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!.contains { $0.name == "query" && $0.value == "budget & cost" })
        let before = client.config
        FixtureProtocol.replies = [(401, "{}")]
        do { try await client.connect(baseURL: URL(string: "https://wrong.invalid")!, username: "wrong", password: "wrong"); fatalError("Failed login saved") } catch { precondition(client.config == before) }
        Keychain.failWrite = true
        FixtureProtocol.replies = [(200, #"{"token":"replacement"}"#)]
        do { try await client.connect(baseURL: server, username: "user", password: "new"); fatalError("Store error hidden") } catch { precondition(client.config == before) }
        Keychain.failWrite = false
        let restored = PBClient(session: fixtureSession(), defaults: defaults)
        precondition(restored.config?.baseURL == server)
        FixtureProtocol.replies = [(200, "{}")]
        do { _ = try await client.listDocuments(); fatalError("Malformed list accepted") } catch { }
        do { try await client.connect(baseURL: URL(string: "http://fixture.invalid")!, username: "user", password: "pass"); fatalError("Insecure account accepted") } catch { }
        try await checkTransportBounds()
        print("Paperless client: token exchange, search, decoding, secure-store failure and restore passed")
    }
}
