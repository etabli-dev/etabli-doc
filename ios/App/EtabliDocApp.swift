// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

@main
struct EtabliDocApp: App {
    @State private var client = PBClient()

    init() { AppearancePreference.migrateLegacyMode(in: .standard) }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(client)
                .etabliAppearance()
        }
    }
}
