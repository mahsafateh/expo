/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.devsupport

import android.content.Context
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.common.SurfaceDelegateFactory
import com.facebook.react.devsupport.interfaces.DevBundleDownloadListener
import com.facebook.react.devsupport.interfaces.DevLoadingViewManager
import com.facebook.react.devsupport.interfaces.DevSupportManager
import com.facebook.react.devsupport.interfaces.PausedInDebuggerOverlayManager
import com.facebook.react.devsupport.interfaces.RedBoxHandler
import com.facebook.react.packagerconnection.RequestHandler

//
// Expo: This is a copy of react-native's {@link com.facebook.react.devsupport.BridgelessDevSupportManager}
// just removing the "final" modifier that we can inherit and reuse.
// From time to time for react-native upgrade, just follow the steps to update the code
//   1. Copy the contents from BridgelessDevSupportManager to this file.
//   2. Rename the class to NonFinalBridgelessDevSupportManager.
//   3. Change "public" modifier -> "open"
//   4. Revert the comment
//

/**
 * An implementation of [DevSupportManager] that extends the functionality in
 * [DevSupportManagerBase] with some additional, more flexible APIs for asynchronously loading the
 * JS bundle.
 *
 * @constructor The primary constructor mirrors the same constructor we have for
 *   [BridgeDevSupportManager] and
 *     * is kept for backward compatibility.
 */
open class NonFinalBridgelessDevSupportManager(
  applicationContext: Context,
  reactInstanceManagerHelper: ReactInstanceDevHelper,
  packagerPathForJSBundleName: String?,
  enableOnCreate: Boolean,
  redBoxHandler: RedBoxHandler?,
  devBundleDownloadListener: DevBundleDownloadListener?,
  minNumShakes: Int,
  customPackagerCommandHandlers: Map<String, RequestHandler>?,
  surfaceDelegateFactory: SurfaceDelegateFactory?,
  devLoadingViewManager: DevLoadingViewManager?,
  pausedInDebuggerOverlayManager: PausedInDebuggerOverlayManager?
) :
  DevSupportManagerBase(
    applicationContext,
    reactInstanceManagerHelper,
    packagerPathForJSBundleName,
    enableOnCreate,
    redBoxHandler,
    devBundleDownloadListener,
    minNumShakes,
    customPackagerCommandHandlers,
    surfaceDelegateFactory,
    devLoadingViewManager,
    pausedInDebuggerOverlayManager
  ) {

  constructor(
    context: Context,
    reactInstanceManagerHelper: ReactInstanceDevHelper,
    packagerPathForJSBundleName: String?
  ) : this(
    applicationContext = context.applicationContext,
    reactInstanceManagerHelper = reactInstanceManagerHelper,
    packagerPathForJSBundleName = packagerPathForJSBundleName,
    enableOnCreate = true,
    redBoxHandler = null,
    devBundleDownloadListener = null,
    minNumShakes = 2,
    customPackagerCommandHandlers = null,
    surfaceDelegateFactory = null,
    devLoadingViewManager = null,
    pausedInDebuggerOverlayManager = null
  )

  override val uniqueTag: String
    get() = "Bridgeless"

  override fun handleReloadJS() {
    UiThreadUtil.assertOnUiThread()
    // dismiss redbox if exists
    hideRedboxDialog()
    reactInstanceDevHelper.reload("BridgelessDevSupportManager.handleReloadJS()")
  }
}
