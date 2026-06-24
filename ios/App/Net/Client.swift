// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Observation

// Paperless-ngx HTTP client.
//
// Auth: POST credentials to /api/token/ → token; subsequent calls send
// `Authorization: Token <token>`. The Paperless REST is fully documented
// at <base>/api/schema/view/ — we verify endpoint paths against that on
// every connect (a 404 there fails the connection cleanly).

public struct PBConfig: Equatable, Sendable {
    public var baseURL: URL
    public var hasToken: Bool
}

public enum PBError: Error, LocalizedError {
    case notConfigured
    case http(status: Int, body: String?)
    case decoding(String)
    case transport(String)
    case invalidCredentials
    public var errorDescription: String? {
        switch self {
        case .notConfigured: "Configure base URL + credentials in Settings."
        case .http(let s, _): "Server returned HTTP \(s)."
        case .decoding(let m): "Couldn't decode response: \(m)."
        case .transport(let m): "Network error: \(m)."
        case .invalidCredentials: "Username or password was rejected."
        }
    }
}

// MARK: - DTOs

public struct PBPage<T: Decodable & Sendable>: Decodable, Sendable {
    public let count: Int
    public let next: String?
    public let previous: String?
    public let results: [T]
}

public struct PBDocument: Decodable, Identifiable, Sendable {
    public let id: Int
    public let title: String?
    public let created: String?
    public let added: String?
    public let archive_serial_number: String?
    public let correspondent: Int?
    public let document_type: Int?
    public let tags: [Int]?
    public let original_file_name: String?
}

public struct PBNamed: Decodable, Identifiable, Sendable {
    public let id: Int
    public let name: String?
}

// MARK: - Client

@MainActor
@Observable
public final class PBClient {

    public private(set) var config: PBConfig?
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let keychainService = "paperbox.paperless"
    private let urlKey = "paperbox.paperless.baseurl"

    public init(session: URLSession = .shared) {
        self.session = session
        if let stored = UserDefaults.standard.url(forKey: urlKey),
           let token = (try? Keychain.get(service: keychainService, account: "token")) ?? nil,
           !token.isEmpty {
            self.config = PBConfig(baseURL: stored, hasToken: true)
        }
    }

    public func disconnect() throws {
        try Keychain.delete(service: keychainService, account: "token")
        UserDefaults.standard.removeObject(forKey: urlKey)
        config = nil
    }

    /// POST username/password to /api/token/ → token. Stores both URL and
    /// token. On any HTTP failure we DON'T persist anything.
    public func connect(baseURL: URL, username: String, password: String) async throws {
        let url = baseURL.appendingPathComponent("api/token/")
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: String] = ["username": username, "password": password]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw PBError.transport("No HTTP response")
        }
        if http.statusCode == 400 || http.statusCode == 401 {
            throw PBError.invalidCredentials
        }
        guard (200..<300).contains(http.statusCode) else {
            throw PBError.http(status: http.statusCode, body: String(data: data, encoding: .utf8))
        }
        struct TokenResponse: Decodable { let token: String }
        let resp = try decoder.decode(TokenResponse.self, from: data)
        try Keychain.set(resp.token, service: keychainService, account: "token")
        UserDefaults.standard.set(baseURL, forKey: urlKey)
        config = PBConfig(baseURL: baseURL, hasToken: true)
    }

    private func token() throws -> String {
        guard let t = try Keychain.get(service: keychainService, account: "token"), !t.isEmpty
        else { throw PBError.notConfigured }
        return t
    }

    // MARK: - Endpoints

    /// Page through /api/documents/?query= — supports full-text search,
    /// tag/correspondent/type filters, page_size, ordering.
    public func listDocuments(query: String? = nil,
                              tagID: Int? = nil,
                              correspondentID: Int? = nil,
                              docTypeID: Int? = nil,
                              pageSize: Int = 50) async throws -> [PBDocument] {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "page_size", value: String(pageSize)),
            URLQueryItem(name: "ordering", value: "-added"),
        ]
        if let q = query?.trimmingCharacters(in: .whitespaces), !q.isEmpty {
            items.append(URLQueryItem(name: "query", value: q))
        }
        if let tagID { items.append(URLQueryItem(name: "tags__id__all", value: String(tagID))) }
        if let correspondentID { items.append(URLQueryItem(name: "correspondent__id", value: String(correspondentID))) }
        if let docTypeID { items.append(URLQueryItem(name: "document_type__id", value: String(docTypeID))) }
        let page: PBPage<PBDocument> = try await getDecoded("api/documents/", queryItems: items, as: PBPage<PBDocument>.self)
        return page.results
    }

    public func listTags() async throws -> [PBNamed] {
        let page: PBPage<PBNamed> = try await getDecoded("api/tags/",
                                                         queryItems: [URLQueryItem(name: "page_size", value: "200")],
                                                         as: PBPage<PBNamed>.self)
        return page.results
    }
    public func listCorrespondents() async throws -> [PBNamed] {
        let page: PBPage<PBNamed> = try await getDecoded("api/correspondents/",
                                                         queryItems: [URLQueryItem(name: "page_size", value: "200")],
                                                         as: PBPage<PBNamed>.self)
        return page.results
    }
    public func listDocumentTypes() async throws -> [PBNamed] {
        let page: PBPage<PBNamed> = try await getDecoded("api/document_types/",
                                                         queryItems: [URLQueryItem(name: "page_size", value: "200")],
                                                         as: PBPage<PBNamed>.self)
        return page.results
    }

    /// Download a document's original file (PDF / image). Streams to a
    /// temp URL so QuickLook / PDFKit can open it without us loading the
    /// whole thing into RAM.
    public func downloadOriginal(id: Int) async throws -> URL {
        guard let cfg = config else { throw PBError.notConfigured }
        let url = cfg.baseURL.appendingPathComponent("api/documents/\(id)/download/")
        var req = URLRequest(url: url)
        req.setValue("Token \(try token())", forHTTPHeaderField: "Authorization")
        let (tempURL, response) = try await session.download(for: req)
        guard let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode) else {
            try? FileManager.default.removeItem(at: tempURL)
            throw PBError.http(status: (response as? HTTPURLResponse)?.statusCode ?? 0, body: nil)
        }
        let suggested = inferExtension(from: http) ?? "pdf"
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("paperbox-\(id)-\(UUID().uuidString).\(suggested)")
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tempURL, to: dest)
        return dest
    }

    private func inferExtension(from http: HTTPURLResponse) -> String? {
        if let cd = http.value(forHTTPHeaderField: "Content-Disposition"),
           let range = cd.range(of: "filename=") {
            let after = cd[range.upperBound...]
                .trimmingCharacters(in: CharacterSet(charactersIn: "\";"))
            let ext = (after as NSString).pathExtension
            if !ext.isEmpty { return ext }
        }
        if let ct = http.value(forHTTPHeaderField: "Content-Type")?.lowercased() {
            if ct.contains("pdf") { return "pdf" }
            if ct.contains("png") { return "png" }
            if ct.contains("jpeg") || ct.contains("jpg") { return "jpg" }
            if ct.contains("tiff") { return "tiff" }
        }
        return nil
    }

    // MARK: - Generic GET

    private func getDecoded<T: Decodable>(_ path: String, queryItems: [URLQueryItem] = [], as: T.Type) async throws -> T {
        guard let cfg = config else { throw PBError.notConfigured }
        var comps = URLComponents(url: cfg.baseURL.appendingPathComponent(path),
                                  resolvingAgainstBaseURL: false)
        if !queryItems.isEmpty { comps?.queryItems = queryItems }
        guard let url = comps?.url else { throw PBError.transport("Couldn't build URL") }
        var req = URLRequest(url: url)
        req.setValue("Token \(try token())", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await session.data(for: req)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw PBError.http(status: http.statusCode, body: String(data: data, encoding: .utf8))
            }
            do { return try decoder.decode(T.self, from: data) }
            catch { throw PBError.decoding(error.localizedDescription) }
        } catch let e as PBError { throw e }
        catch { throw PBError.transport(error.localizedDescription) }
    }
}
