package expo.modules.updates

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.facebook.react.bridge.ReactContext
import com.facebook.react.devsupport.interfaces.DevSupportManager
import expo.modules.easclient.EASClientID
import expo.modules.kotlin.exception.CodedException
import expo.modules.updates.db.DatabaseHolder
import expo.modules.updates.db.Reaper
import expo.modules.updates.db.UpdatesDatabase
import expo.modules.updates.db.entity.AssetEntity
import expo.modules.updates.db.entity.UpdateEntity
import expo.modules.updates.events.NoOpUpdatesEventManager
import expo.modules.updates.launcher.DatabaseLauncher
import expo.modules.updates.launcher.Launcher
import expo.modules.updates.loader.FileDownloader
import expo.modules.updates.loader.Loader
import expo.modules.updates.loader.RemoteLoader
import expo.modules.updates.loader.UpdateDirective
import expo.modules.updates.logging.UpdatesErrorCode
import expo.modules.updates.logging.UpdatesLogger
import expo.modules.updates.reloadscreen.ReloadScreenManager
import expo.modules.updates.selectionpolicy.LauncherSelectionPolicySingleUpdate
import expo.modules.updates.selectionpolicy.ReaperSelectionPolicyDevelopmentClient
import expo.modules.updates.selectionpolicy.SelectionPolicy
import expo.modules.updates.selectionpolicy.SelectionPolicyFactory
import expo.modules.updates.statemachine.UpdatesStateContext
import expo.modules.updatesinterface.UpdatesInterface
import expo.modules.updatesinterface.UpdatesInterfaceCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Main entry point to expo-updates in development builds with expo-dev-client. Similar to EnabledUpdatesController
 * in that it keeps track of updates state, but provides capabilities that are not usually exposed but
 * that expo-dev-client needs (launching and downloading a specific
 * update by URL, allowing dynamic configuration, introspecting the database). The behavior of this
 * class differs enough that it is implemented independently from EnabledUpdatesController.
 *
 * Implements the external UpdatesInterface from the expo-updates-interface package. This allows
 * expo-dev-client to compile without needing expo-updates to be installed.
 */
class UpdatesDevLauncherController(
  private val context: Context,
  initialUpdatesConfiguration: UpdatesConfiguration?,
  override val updatesDirectory: File?,
  private val updatesDirectoryException: Exception?
) : IUpdatesController, UpdatesInterface {
  override val eventManager = NoOpUpdatesEventManager()
  override var updatesInterfaceCallbacks: WeakReference<UpdatesInterfaceCallbacks>? = null

  private var launcher: Launcher? = null

  private val logger = UpdatesLogger(context.filesDir)

  private var previousUpdatesConfiguration: UpdatesConfiguration? = null
  private var updatesConfiguration: UpdatesConfiguration? = initialUpdatesConfiguration

  private val databaseHolder = DatabaseHolder(UpdatesDatabase.getInstance(context, Dispatchers.IO))
  private val controllerScope = CoroutineScope(Dispatchers.IO)

  private var mSelectionPolicy: SelectionPolicy? = null
  private var defaultSelectionPolicy: SelectionPolicy = SelectionPolicyFactory.createFilterAwarePolicy(
    initialUpdatesConfiguration?.getRuntimeVersion() ?: "1"
  )
  private val selectionPolicy: SelectionPolicy
    get() = mSelectionPolicy ?: defaultSelectionPolicy

  private fun setNextSelectionPolicy(selectionPolicy: SelectionPolicy?) {
    mSelectionPolicy = selectionPolicy
  }

  private fun resetSelectionPolicyToDefault() {
    mSelectionPolicy = null
  }

  private fun setDefaultSelectionPolicy(selectionPolicy: SelectionPolicy) {
    defaultSelectionPolicy = selectionPolicy
  }

  @get:Synchronized
  override val launchAssetFile: String?
    get() {
      logger.warn("launchAssetFile should not be called from expo-dev-client build, except for Detox testing")
      return null
    }

  override val bundleAssetName: String
    get() = throw Exception("IUpdatesController.bundleAssetName should not be called in dev client")

  override val reloadScreenManager = ReloadScreenManager()

  override fun onEventListenerStartObserving() {
    // no-op for UpdatesDevLauncherController
  }

  override fun onDidCreateDevSupportManager(devSupportManager: DevSupportManager) {}

  override fun onDidCreateReactInstance(reactContext: ReactContext) {}

  override fun onReactInstanceException(exception: java.lang.Exception) {}

  override val isActiveController = false

  override fun start() {
    // no-op for UpdatesDevLauncherController
  }

  val launchedUpdate: UpdateEntity?
    get() = launcher?.launchedUpdate

  private val localAssetFiles: Map<AssetEntity, String>?
    get() = launcher?.localAssetFiles

  private val isUsingEmbeddedAssets: Boolean
    get() = launcher?.isUsingEmbeddedAssets ?: false

  override fun reset() {
    launcher = null
  }

  override val runtimeVersion: String?
    get() = updatesConfiguration?.getRuntimeVersion()

  override val updateUrl: Uri?
    get() = updatesConfiguration?.updateUrl

  /**
   * Fetch an update using a dynamically generated configuration object (including a potentially
   * different update URL than the one embedded in the build).
   */
  override fun fetchUpdateWithConfiguration(
    configuration: HashMap<String, Any>,
    callback: UpdatesInterface.UpdateCallback
  ) {
    val newUpdatesConfiguration: UpdatesConfiguration
    try {
      newUpdatesConfiguration = createUpdatesConfiguration(configuration)
    } catch (e: Exception) {
      callback.onFailure(e)
      return
    }
    check(updatesDirectory != null)

    // since controller is a singleton, save its config so we can reset to it if our request fails
    previousUpdatesConfiguration = updatesConfiguration
    updatesConfiguration = newUpdatesConfiguration

    setDevelopmentSelectionPolicy()

    val fileDownloader = FileDownloader(context.filesDir, EASClientID(context).uuid.toString(), updatesConfiguration!!, logger)
    val loader = RemoteLoader(
      context,
      updatesConfiguration!!,
      logger,
      databaseHolder.database,
      fileDownloader,
      updatesDirectory,
      null
    )
    controllerScope.launch {
      val progressJob = launch {
        loader.progressFlow.collect { progress ->
          callback.onProgress(progress.successfulAssetCount, progress.failedAssetCount, progress.totalAssetCount)
        }
      }

      try {
        val loaderResult = loader.load { updateResponse ->
          val updateDirective = updateResponse.directiveUpdateResponsePart?.updateDirective
          if (updateDirective != null) {
            return@load Loader.OnUpdateResponseLoadedResult(
              shouldDownloadManifestIfPresentInResponse = when (updateDirective) {
                is UpdateDirective.RollBackToEmbeddedUpdateDirective -> false
                is UpdateDirective.NoUpdateAvailableUpdateDirective -> false
              }
            )
          }

          val update = updateResponse.manifestUpdateResponsePart?.update
            ?: return@load Loader.OnUpdateResponseLoadedResult(shouldDownloadManifestIfPresentInResponse = false)
          Loader.OnUpdateResponseLoadedResult(shouldDownloadManifestIfPresentInResponse = callback.onManifestLoaded(update.manifest.getRawJson()))
        }

        // the dev launcher doesn't handle roll back to embedded commands
        if (loaderResult.updateEntity == null) {
          callback.onSuccess(null)
          return@launch
        }
        launchUpdate(loaderResult.updateEntity, updatesConfiguration!!, fileDownloader, callback)
      } catch (e: Exception) {
        // reset controller's configuration to what it was before this request
        updatesConfiguration = previousUpdatesConfiguration
        callback.onFailure(e)
      } finally {
        progressJob.cancel()
      }
    }
  }

  override fun isValidUpdatesConfiguration(configuration: HashMap<String, Any>): Boolean {
    return try {
      createUpdatesConfiguration(configuration)
      true
    } catch (e: Exception) {
      logger.error("Invalid updates configuration", e, UpdatesErrorCode.InitializationError)
      false
    }
  }

  @Throws(Exception::class)
  private fun createUpdatesConfiguration(configuration: HashMap<String, Any>): UpdatesConfiguration {
    if (updatesDirectory == null) {
      throw updatesDirectoryException!!
    }

    return when (UpdatesConfiguration.getUpdatesConfigurationValidationResult(context, configuration)) {
      UpdatesConfigurationValidationResult.VALID -> UpdatesConfiguration(context, configuration)
      UpdatesConfigurationValidationResult.INVALID_NOT_ENABLED -> {
        throw Exception("Failed to load update: UpdatesConfiguration object is not enabled")
      }

      UpdatesConfigurationValidationResult.INVALID_MISSING_URL -> {
        throw Exception("Failed to load update: UpdatesConfiguration object must include a valid update URL")
      }

      UpdatesConfigurationValidationResult.INVALID_MISSING_RUNTIME_VERSION -> {
        throw Exception("Failed to load update: UpdatesConfiguration object must include a valid runtime version")
      }
    }
  }

  private fun setDevelopmentSelectionPolicy() {
    resetSelectionPolicyToDefault()
    val currentSelectionPolicy = selectionPolicy
    setDefaultSelectionPolicy(
      SelectionPolicy(
        currentSelectionPolicy.launcherSelectionPolicy,
        currentSelectionPolicy.loaderSelectionPolicy,
        ReaperSelectionPolicyDevelopmentClient()
      )
    )
    resetSelectionPolicyToDefault()
  }

  private suspend fun launchUpdate(
    update: UpdateEntity,
    configuration: UpdatesConfiguration,
    fileDownloader: FileDownloader,
    callback: UpdatesInterface.UpdateCallback
  ) {
    // ensure that we launch the update we want, even if it isn't the latest one
    val currentSelectionPolicy = selectionPolicy
    // Calling `setNextSelectionPolicy` allows the Updates module's `reloadAsync` method to reload
    // with a different (newer) update if one is downloaded, e.g. using `fetchUpdateAsync`. If we
    // set the default selection policy here instead, the update we are launching here would keep
    // being launched by `reloadAsync` even if a newer one is downloaded.
    setNextSelectionPolicy(
      SelectionPolicy(
        LauncherSelectionPolicySingleUpdate(update.id),
        currentSelectionPolicy.loaderSelectionPolicy,
        currentSelectionPolicy.reaperSelectionPolicy
      )
    )

    val launcher = DatabaseLauncher(
      context,
      configuration,
      updatesDirectory!!,
      fileDownloader,
      selectionPolicy,
      logger,
      controllerScope
    )
    try {
      launcher.launch(databaseHolder.database)
      this@UpdatesDevLauncherController.launcher = launcher
      callback.onSuccess(object : UpdatesInterface.Update {
        override val manifest: JSONObject
          get() = launcher.launchedUpdate!!.manifest
        override val launchAssetPath: String
          get() = launcher.launchAssetFile!!
      })
      runReaper()
    } catch (e: Exception) {
      // reset controller's configuration to what it was before this request
      updatesConfiguration = previousUpdatesConfiguration
      callback.onFailure(e)
    }
  }

  private fun getDatabase(): UpdatesDatabase = databaseHolder.database

  private fun runReaper() {
    controllerScope.launch {
      updatesConfiguration?.let {
        val databaseLocal = getDatabase()
        Reaper.reapUnusedUpdates(
          it,
          databaseLocal,
          updatesDirectory,
          launchedUpdate,
          selectionPolicy
        )
      }
    }
  }

  class NotAvailableInDevClientException(message: String) : CodedException(message)

  override fun getConstantsForModule(): IUpdatesController.UpdatesModuleConstants {
    return IUpdatesController.UpdatesModuleConstants(
      launchedUpdate = launchedUpdate,
      launchDuration = null,
      embeddedUpdate = null, // no embedded update in debug builds
      emergencyLaunchException = updatesDirectoryException,
      isEnabled = true,
      isUsingEmbeddedAssets = isUsingEmbeddedAssets,
      runtimeVersion = updatesConfiguration?.runtimeVersionRaw ?: "1",
      checkOnLaunch = updatesConfiguration?.checkOnLaunch ?: UpdatesConfiguration.CheckAutomaticallyConfiguration.ALWAYS,
      requestHeaders = updatesConfiguration?.requestHeaders ?: mapOf(),
      localAssetFiles = localAssetFiles,
      shouldDeferToNativeForAPIMethodAvailabilityInDevelopment = true,
      initialContext = UpdatesStateContext()
    )
  }

  override suspend fun relaunchReactApplicationForModule() = suspendCancellableCoroutine { continuation ->
    this.updatesInterfaceCallbacks?.get()?.onRequestRelaunch()
    continuation.resume(Unit)
  }

  override suspend fun checkForUpdate(): IUpdatesController.CheckForUpdateResult {
    throw NotAvailableInDevClientException("Updates.checkForUpdateAsync() is not supported in development builds.")
  }

  override suspend fun fetchUpdate(): IUpdatesController.FetchUpdateResult {
    throw NotAvailableInDevClientException("Updates.fetchUpdateAsync() is not supported in development builds.")
  }

  override suspend fun getExtraParams(): Bundle {
    throw NotAvailableInDevClientException("Updates.getExtraParamsAsync() is not supported in development builds.")
  }

  override suspend fun setExtraParam(
    key: String,
    value: String?
  ) {
    throw NotAvailableInDevClientException("Updates.setExtraParamAsync() is not supported in development builds.")
  }

  override fun setUpdateURLAndRequestHeadersOverride(configOverride: UpdatesConfigurationOverride?) {
    throw NotAvailableInDevClientException("Updates.setUpdateURLAndRequestHeadersOverride() is not supported in development builds.")
  }

  override fun shutdown() {
    // no-op
  }

  companion object {
    private val TAG = UpdatesDevLauncherController::class.java.simpleName
  }
}
