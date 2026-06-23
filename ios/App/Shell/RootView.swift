// Copyright 2026 Raban Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI

struct RootView: View {
    var body: some View {
        TabView {
            NavigationStack { DocumentsView() }
                .tabItem { Label("Documents", systemImage: "doc.text.magnifyingglass") }
            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gear") }
        }
    }
}
