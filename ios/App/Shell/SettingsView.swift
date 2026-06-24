// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI

struct SettingsView: View {
    @Environment(PBClient.self) private var client
    @AppStorage(ThemePreference.userDefaultsKey) private var themeRaw: String = ThemePreference.system.rawValue
    private var theme: ThemePreference { ThemePreference(rawValue: themeRaw) ?? .system }

    @State private var urlText = ""
    @State private var username = ""
    @State private var password = ""
    @State private var connecting = false
    @State private var error: String?

    var body: some View {
        ZStack {
            Theme.Color.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.lg) {
                    PromptHeader(["settings"])
                    Text("Connection")
                        .font(Theme.Font.display).foregroundStyle(Theme.Color.ink)
                    if let cfg = client.config {
                        connectedCard(cfg)
                    } else {
                        connectCard
                    }
                    Card(title: "appearance", systemImage: "paintbrush") {
                        Picker("Theme", selection: Binding(
                            get: { theme }, set: { themeRaw = $0.rawValue }
                        )) {
                            ForEach(ThemePreference.allCases) { p in
                                Label(p.label, systemImage: p.systemImage).tag(p)
                            }
                        }.pickerStyle(.segmented)
                    }
                    Card(title: "about", systemImage: "info.circle") {
                        Text("EtabliDoc").font(Theme.Font.headline).foregroundStyle(Theme.Color.ink)
                        Text("Paperless-ngx companion — search, filter, view PDFs from your document archive. No analytics; no tracking.")
                            .font(Theme.Font.body).foregroundStyle(Theme.Color.faint)
                    }
                }.padding(Theme.Space.lg)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
    }

    private var connectCard: some View {
        Card(title: "connect to paperless-ngx", systemImage: "link") {
            VStack(alignment: .leading, spacing: Theme.Space.md) {
                Text("Enter your Paperless URL, username, and password. EtabliDoc exchanges them for an API token over /api/token/ and stores only the token in the iOS Keychain.")
                    .font(Theme.Font.body).foregroundStyle(Theme.Color.ink)
                field("base URL", text: $urlText, placeholder: "https://paperless.example.com")
                field("username", text: $username, placeholder: "your username")
                field("password", text: $password, placeholder: "your password", secure: true)
                if let error {
                    Text(error).font(Theme.Font.body).foregroundStyle(Theme.Color.danger)
                }
                PrimaryButton(connecting ? "Connecting…" : "Connect", systemImage: "checkmark.seal",
                              enabled: !urlText.isEmpty && !username.isEmpty && !password.isEmpty && !connecting) {
                    connect()
                }
            }
        }
    }

    private func connectedCard(_ cfg: PBConfig) -> some View {
        Card(title: "connected", systemImage: "checkmark.circle") {
            VStack(alignment: .leading, spacing: Theme.Space.md) {
                MonoLabel(cfg.baseURL.absoluteString)
                Button(role: .destructive) {
                    try? client.disconnect()
                } label: {
                    Text("Disconnect").font(Theme.Font.body.weight(.semibold))
                        .padding(.horizontal, Theme.Space.md).padding(.vertical, Theme.Space.sm)
                        .foregroundStyle(Theme.Color.surface)
                        .background(Theme.Color.danger)
                        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.sm))
                }.buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func field(_ label: String, text: Binding<String>, placeholder: String, secure: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            MonoLabel(label, color: Theme.Color.faint)
            Group {
                if secure { SecureField(placeholder, text: text) }
                else      { TextField(placeholder, text: text) }
            }
            .textFieldStyle(.plain)
            .font(Theme.Font.monoBody).foregroundStyle(Theme.Color.ink)
            .padding(Theme.Space.sm).background(Theme.Color.paper)
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.sm)
                .strokeBorder(Theme.Color.hairline, lineWidth: 1))
            .autocorrectionDisabled().textInputAutocapitalization(.never)
        }
    }

    private func connect() {
        guard let url = URL(string: urlText.trimmingCharacters(in: .whitespaces)) else {
            error = "Invalid URL"; return
        }
        connecting = true; error = nil
        Task {
            do {
                try await client.connect(baseURL: url, username: username, password: password)
            } catch {
                self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
            connecting = false
        }
    }
}
