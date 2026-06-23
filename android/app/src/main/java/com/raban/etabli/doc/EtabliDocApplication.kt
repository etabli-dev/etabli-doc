// Copyright 2026 Raban Heller
// SPDX-License-Identifier: Apache-2.0

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
