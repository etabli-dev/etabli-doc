// Copyright 2026 R. Heller
// SPDX-License-Identifier: LicenseRef-Proprietary

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
