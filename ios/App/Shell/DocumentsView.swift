// Copyright 2026 Raban Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import QuickLook

struct DocumentsView: View {
    @Environment(PBClient.self) private var client

    @State private var documents: [PBDocument] = []
    @State private var tags: [PBNamed] = []
    @State private var correspondents: [PBNamed] = []
    @State private var docTypes: [PBNamed] = []
    @State private var loading = false
    @State private var error: String?
    @State private var query: String = ""
    @State private var selectedTagID: Int?
    @State private var selectedCorrespondentID: Int?
    @State private var selectedDocTypeID: Int?
    @State private var previewURL: URL?
    @State private var downloadingID: Int?
    @State private var searchTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            Theme.Color.paper.ignoresSafeArea()
            content
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { await initialLoad() }
        .quickLookPreview($previewURL)
        .onChange(of: query) { _, _ in scheduleSearch() }
        .onChange(of: selectedTagID) { _, _ in scheduleSearch() }
        .onChange(of: selectedCorrespondentID) { _, _ in scheduleSearch() }
        .onChange(of: selectedDocTypeID) { _, _ in scheduleSearch() }
    }

    @ViewBuilder
    private var content: some View {
        if client.config == nil {
            EmptyState(title: "not connected",
                       detail: "Open Settings to enter your Paperless URL + login.",
                       systemImage: "link.badge.plus")
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.lg) {
                    PromptHeader(["documents"])
                    Text("Search")
                        .font(Theme.Font.display).foregroundStyle(Theme.Color.ink)
                    searchBar
                    filterChips
                    body_
                }.padding(Theme.Space.lg)
            }
            .refreshable { await load() }
        }
    }

    @ViewBuilder
    private var body_: some View {
        if loading && documents.isEmpty {
            LoadingState("fetching documents…").frame(height: 240)
        } else if let error, documents.isEmpty {
            ErrorState(title: "couldn't fetch", detail: error, retry: { Task { await load() } })
                .frame(height: 240)
        } else if documents.isEmpty {
            EmptyState(title: "no matches", detail: "Try a different filter / search term.",
                       systemImage: "tray").frame(height: 240)
        } else {
            Card(title: "\(documents.count) documents", systemImage: "doc.text") {
                VStack(spacing: 0) {
                    ForEach(documents) { d in
                        Button { Task { await open(d) } } label: {
                            ListRow(
                                title: d.title ?? "(untitled)",
                                metadata: subtitle(d),
                                leading: { Image(systemName: "doc.text").foregroundStyle(Theme.Color.accent) },
                                trailing: {
                                    if downloadingID == d.id {
                                        ProgressView().tint(Theme.Color.accent)
                                    } else {
                                        Image(systemName: "chevron.right")
                                            .font(Theme.Font.mono).foregroundStyle(Theme.Color.faint)
                                    }
                                }
                            )
                        }.buttonStyle(.plain)
                        if d.id != documents.last?.id {
                            Divider().background(Theme.Color.hairline)
                        }
                    }
                }
            }
        }
    }

    private var searchBar: some View {
        HStack(spacing: Theme.Space.sm) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.Color.faint)
            TextField("full-text search", text: $query)
                .textFieldStyle(.plain)
                .font(Theme.Font.monoBody).foregroundStyle(Theme.Color.ink)
                .autocorrectionDisabled().textInputAutocapitalization(.never)
            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.Color.faint)
                }.buttonStyle(.plain)
            }
        }
        .padding(Theme.Space.sm)
        .background(Theme.Color.surface)
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.sm)
            .strokeBorder(Theme.Color.hairline, lineWidth: 1))
    }

    private var filterChips: some View {
        HStack(spacing: Theme.Space.sm) {
            menuChip(label: tagLabel, active: selectedTagID != nil) {
                Button("All tags") { selectedTagID = nil }
                Divider()
                ForEach(tags) { t in
                    Button(t.name ?? "—") { selectedTagID = t.id }
                }
            }
            menuChip(label: correspondentLabel, active: selectedCorrespondentID != nil) {
                Button("All senders") { selectedCorrespondentID = nil }
                Divider()
                ForEach(correspondents) { c in
                    Button(c.name ?? "—") { selectedCorrespondentID = c.id }
                }
            }
            menuChip(label: docTypeLabel, active: selectedDocTypeID != nil) {
                Button("All types") { selectedDocTypeID = nil }
                Divider()
                ForEach(docTypes) { dt in
                    Button(dt.name ?? "—") { selectedDocTypeID = dt.id }
                }
            }
            if anyFilterActive {
                Button {
                    selectedTagID = nil; selectedCorrespondentID = nil; selectedDocTypeID = nil
                    query = ""
                } label: { MonoLabel("clear", color: Theme.Color.accent) }
                    .buttonStyle(.plain)
            }
            Spacer()
        }
    }

    @ViewBuilder
    private func menuChip<C: View>(label: String, active: Bool, @ViewBuilder content: () -> C) -> some View {
        Menu(content: content) {
            HStack(spacing: 4) {
                MonoLabel(label, color: active ? Theme.Color.surface : Theme.Color.ink)
                Image(systemName: "chevron.down").font(.caption2)
                    .foregroundStyle(active ? Theme.Color.surface : Theme.Color.faint)
            }
            .padding(.horizontal, Theme.Space.sm).padding(.vertical, Theme.Space.xs)
            .background(active ? Theme.Color.accent : Theme.Color.paper)
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.sm)
                .strokeBorder(Theme.Color.hairline, lineWidth: 1))
        }
    }

    private var tagLabel: String {
        if let id = selectedTagID, let t = tags.first(where: { $0.id == id }) { return "tag: \(t.name ?? "—")" }
        return "any tag"
    }
    private var correspondentLabel: String {
        if let id = selectedCorrespondentID, let c = correspondents.first(where: { $0.id == id }) { return "from: \(c.name ?? "—")" }
        return "any sender"
    }
    private var docTypeLabel: String {
        if let id = selectedDocTypeID, let dt = docTypes.first(where: { $0.id == id }) { return "type: \(dt.name ?? "—")" }
        return "any type"
    }
    private var anyFilterActive: Bool {
        selectedTagID != nil || selectedCorrespondentID != nil || selectedDocTypeID != nil || !query.isEmpty
    }

    private func subtitle(_ d: PBDocument) -> String {
        var parts: [String] = []
        if let added = d.added {
            parts.append("added " + added.prefix(10))
        }
        if let cid = d.correspondent, let name = correspondents.first(where: { $0.id == cid })?.name {
            parts.append("from \(name)")
        }
        if let tags = d.tags, !tags.isEmpty {
            parts.append("\(tags.count) tag\(tags.count == 1 ? "" : "s")")
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - Actions

    private func initialLoad() async {
        guard client.config != nil else { return }
        // Master data in parallel.
        async let t = (try? await client.listTags()) ?? []
        async let c = (try? await client.listCorrespondents()) ?? []
        async let dt = (try? await client.listDocumentTypes()) ?? []
        tags = await t; correspondents = await c; docTypes = await dt
        await load()
    }

    private func load() async {
        guard client.config != nil else { return }
        loading = true; error = nil
        do {
            documents = try await client.listDocuments(
                query: query.isEmpty ? nil : query,
                tagID: selectedTagID,
                correspondentID: selectedCorrespondentID,
                docTypeID: selectedDocTypeID
            )
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        loading = false
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 250_000_000)
            if Task.isCancelled { return }
            await load()
        }
    }

    private func open(_ d: PBDocument) async {
        guard downloadingID == nil else { return }
        downloadingID = d.id
        defer { downloadingID = nil }
        do { previewURL = try await client.downloadOriginal(id: d.id) }
        catch { self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription }
    }
}
