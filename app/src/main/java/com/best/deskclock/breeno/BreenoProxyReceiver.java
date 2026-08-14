/*
 * Copyright (C) 2026 The Clock Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.breeno;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Ordered-broadcast entry point used by the LSPosed "BreenoProxy" module (running inside
 * com.coloros.alarmclock). Carries the method + args and returns the result Bundle via
 * the ordered-broadcast result extras.
 */
public class BreenoProxyReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.best.deskclock.action.BREENO_PROXY";
    public static final String EXTRA_METHOD = "method";
    public static final String EXTRA_ARGS = "args";
    public static final String EXTRA_TOKEN = "token";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        if (!BreenoProxyProvider.TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN))) {
            setResultCode(Activity.RESULT_CANCELED);
            return;
        }
        final String method = intent.getStringExtra(EXTRA_METHOD);
        final Bundle args = intent.getBundleExtra(EXTRA_ARGS);
        final Bundle result = BreenoProxyProvider.handle(context, method, args);
        setResultExtras(result);
        setResultCode(Activity.RESULT_OK);
    }
}
