// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

package com.raban.etabli.doc

import android.app.Application
import com.raban.etabli.doc.net.PBClient

class EtabliDocApplication : Application() {
    lateinit var client: PBClient
        private set

    override fun onCreate() {
        super.onCreate()
        client = PBClient(this)
    }
}
