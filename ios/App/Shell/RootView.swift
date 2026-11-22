// Copyright 2026 R. Heller
// SPDX-License-Identifier: LicenseRef-Proprietary

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
