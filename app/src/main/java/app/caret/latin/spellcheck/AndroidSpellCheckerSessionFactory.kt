/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.spellcheck

import android.service.textservice.SpellCheckerService.Session

abstract class AndroidSpellCheckerSessionFactory {
    companion object {
        @JvmStatic
        fun newInstance(service: AndroidSpellCheckerService): Session {
            return AndroidSpellCheckerSession(service)
        }
    }
}
