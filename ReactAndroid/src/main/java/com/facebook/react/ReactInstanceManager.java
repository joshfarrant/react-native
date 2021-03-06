/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import android.util.Log;
import android.view.View;

import com.facebook.common.logging.FLog;
import com.facebook.infer.annotation.Assertions;
import com.facebook.infer.annotation.ThreadConfined;
import com.facebook.infer.annotation.ThreadSafe;
import com.facebook.react.bridge.CatalystInstance;
import com.facebook.react.bridge.CatalystInstanceImpl;
import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.JSCJavaScriptExecutor;
import com.facebook.react.bridge.JavaJSExecutor;
import com.facebook.react.bridge.JavaScriptExecutor;
import com.facebook.react.bridge.JavaScriptModule;
import com.facebook.react.bridge.JavaScriptModuleRegistry;
import com.facebook.react.bridge.NativeModuleCallExceptionHandler;
import com.facebook.react.bridge.NativeModuleRegistry;
import com.facebook.react.bridge.NotThreadSafeBridgeIdleDebugListener;
import com.facebook.react.bridge.ProxyJavaScriptExecutor;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactMarker;
import com.facebook.react.bridge.ReactMarkerConstants;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.queue.ReactQueueConfigurationSpec;
import com.facebook.react.common.LifecycleState;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.annotations.VisibleForTesting;
import com.facebook.react.devsupport.DevSupportManagerFactory;
import com.facebook.react.devsupport.ReactInstanceDevCommandsHandler;
import com.facebook.react.devsupport.RedBoxHandler;
import com.facebook.react.devsupport.interfaces.DevSupportManager;
import com.facebook.react.devsupport.interfaces.PackagerStatusCallback;
import com.facebook.react.modules.appregistry.AppRegistry;
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.ReactChoreographer;
import com.facebook.react.modules.debug.interfaces.DeveloperSettings;
import com.facebook.react.uimanager.DisplayMetricsHolder;
import com.facebook.react.uimanager.UIImplementationProvider;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.ViewManager;
import com.facebook.react.views.imagehelper.ResourceDrawableIdHelper;
import com.facebook.soloader.SoLoader;
import com.facebook.systrace.Systrace;
import com.facebook.systrace.SystraceMessage;

import static com.facebook.infer.annotation.ThreadConfined.UI;
import static com.facebook.react.bridge.ReactMarkerConstants.ATTACH_MEASURED_ROOT_VIEWS_END;
import static com.facebook.react.bridge.ReactMarkerConstants.ATTACH_MEASURED_ROOT_VIEWS_START;
import static com.facebook.react.bridge.ReactMarkerConstants.BUILD_NATIVE_MODULE_REGISTRY_END;
import static com.facebook.react.bridge.ReactMarkerConstants.BUILD_NATIVE_MODULE_REGISTRY_START;
import static com.facebook.react.bridge.ReactMarkerConstants.CREATE_CATALYST_INSTANCE_END;
import static com.facebook.react.bridge.ReactMarkerConstants.CREATE_CATALYST_INSTANCE_START;
import static com.facebook.react.bridge.ReactMarkerConstants.CREATE_REACT_CONTEXT_START;
import static com.facebook.react.bridge.ReactMarkerConstants.CREATE_VIEW_MANAGERS_END;
import static com.facebook.react.bridge.ReactMarkerConstants.CREATE_VIEW_MANAGERS_START;
import static com.facebook.react.bridge.ReactMarkerConstants.PRE_SETUP_REACT_CONTEXT_END;
import static com.facebook.react.bridge.ReactMarkerConstants.PRE_SETUP_REACT_CONTEXT_START;
import static com.facebook.react.bridge.ReactMarkerConstants.PROCESS_PACKAGES_END;
import static com.facebook.react.bridge.ReactMarkerConstants.PROCESS_PACKAGES_START;
import static com.facebook.react.bridge.ReactMarkerConstants.SETUP_REACT_CONTEXT_END;
import static com.facebook.react.bridge.ReactMarkerConstants.SETUP_REACT_CONTEXT_START;
import static com.facebook.systrace.Systrace.TRACE_TAG_REACT_APPS;
import static com.facebook.systrace.Systrace.TRACE_TAG_REACT_JAVA_BRIDGE;
import static com.facebook.systrace.Systrace.TRACE_TAG_REACT_JSC_CALLS;

/**
 * This class is managing instances of {@link CatalystInstance}. It exposes a way to configure
 * catalyst instance using {@link ReactPackage} and keeps track of the lifecycle of that
 * instance. It also sets up connection between the instance and developers support functionality
 * of the framework.
 *
 * An instance of this manager is required to start JS application in {@link ReactRootView} (see
 * {@link ReactRootView#startReactApplication} for more info).
 *
 * The lifecycle of the instance of {@link ReactInstanceManager} should be bound to the
 * activity that owns the {@link ReactRootView} that is used to render react application using this
 * instance manager (see {@link ReactRootView#startReactApplication}). It's required to pass owning
 * activity's lifecycle events to the instance manager (see {@link #onHostPause}, {@link
 * #onHostDestroy} and {@link #onHostResume}).
 *
 * To instantiate an instance of this class use {@link #builder}.
 */
@ThreadSafe
public class ReactInstanceManager {

  /**
   * Listener interface for react instance events.
   */
  public interface ReactInstanceEventListener {
    /**
     * Called when the react context is initialized (all modules registered). Always called on the
     * UI thread.
     */
    void onReactContextInitialized(ReactContext context);
  }

  private final List<ReactRootView> mAttachedRootViews = Collections.synchronizedList(
    new ArrayList<ReactRootView>());

  private volatile LifecycleState mLifecycleState;
  private @Nullable @ThreadConfined(UI) ReactContextInitParams mPendingReactContextInitParams;
  private volatile @Nullable Thread mCreateReactContextThread;

  /* accessed from any thread */
  private final @Nullable JSBundleLoader mBundleLoader; /* path to JS bundle on file system */
  private final @Nullable String mJSMainModuleName; /* path to JS bundle root on packager server */
  private final List<ReactPackage> mPackages;
  private final DevSupportManager mDevSupportManager;
  private final boolean mUseDeveloperSupport;
  private final @Nullable NotThreadSafeBridgeIdleDebugListener mBridgeIdleDebugListener;
  private @Nullable volatile ReactContext mCurrentReactContext;
  private final Context mApplicationContext;
  private @Nullable @ThreadConfined(UI) DefaultHardwareBackBtnHandler mDefaultBackButtonImpl;
  private @Nullable Activity mCurrentActivity;
  private final Collection<ReactInstanceEventListener> mReactInstanceEventListeners =
      Collections.synchronizedSet(new HashSet<ReactInstanceEventListener>());
  private volatile boolean mHasStartedCreatingInitialContext = false;
  private final UIImplementationProvider mUIImplementationProvider;
  private final MemoryPressureRouter mMemoryPressureRouter;
  private final @Nullable NativeModuleCallExceptionHandler mNativeModuleCallExceptionHandler;
  private final JSCConfig mJSCConfig;
  private final boolean mLazyNativeModulesEnabled;
  private final boolean mLazyViewManagersEnabled;
  private final boolean mSetupReactContextInBackgroundEnabled;
  private final boolean mUseSeparateUIBackgroundThread;
  private final int mMinNumShakes;

  private final ReactInstanceDevCommandsHandler mDevInterface =
      new ReactInstanceDevCommandsHandler() {

        @Override
        public void onReloadWithJSDebugger(JavaJSExecutor.Factory jsExecutorFactory) {
          ReactInstanceManager.this.onReloadWithJSDebugger(jsExecutorFactory);
        }

        @Override
        public void onJSBundleLoadedFromServer() {
          ReactInstanceManager.this.onJSBundleLoadedFromServer();
        }

        @Override
        public void toggleElementInspector() {
          ReactInstanceManager.this.toggleElementInspector();
        }
      };

  private final DefaultHardwareBackBtnHandler mBackBtnHandler =
      new DefaultHardwareBackBtnHandler() {
        @Override
        public void invokeDefaultOnBackPressed() {
          ReactInstanceManager.this.invokeDefaultOnBackPressed();
        }
      };

  private class ReactContextInitParams {
    private final JavaScriptExecutor.Factory mJsExecutorFactory;
    private final JSBundleLoader mJsBundleLoader;

    public ReactContextInitParams(
        JavaScriptExecutor.Factory jsExecutorFactory,
        JSBundleLoader jsBundleLoader) {
      mJsExecutorFactory = Assertions.assertNotNull(jsExecutorFactory);
      mJsBundleLoader = Assertions.assertNotNull(jsBundleLoader);
    }

    public JavaScriptExecutor.Factory getJsExecutorFactory() {
      return mJsExecutorFactory;
    }

    public JSBundleLoader getJsBundleLoader() {
      return mJsBundleLoader;
    }
  }

  /**
   * Creates a builder that is capable of creating an instance of {@link ReactInstanceManager}.
   */
  public static ReactInstanceManagerBuilder builder() {
    return new ReactInstanceManagerBuilder();
  }

  /* package */ ReactInstanceManager(
    Context applicationContext,
    @Nullable Activity currentActivity,
    @Nullable DefaultHardwareBackBtnHandler defaultHardwareBackBtnHandler,
    @Nullable JSBundleLoader bundleLoader,
    @Nullable String jsMainModuleName,
    List<ReactPackage> packages,
    boolean useDeveloperSupport,
    @Nullable NotThreadSafeBridgeIdleDebugListener bridgeIdleDebugListener,
    LifecycleState initialLifecycleState,
    UIImplementationProvider uiImplementationProvider,
    NativeModuleCallExceptionHandler nativeModuleCallExceptionHandler,
    JSCConfig jscConfig,
    @Nullable RedBoxHandler redBoxHandler,
    boolean lazyNativeModulesEnabled,
    boolean lazyViewManagersEnabled,
    boolean setupReactContextInBackgroundEnabled,
    boolean useSeparateUIBackgroundThread,
    int minNumShakes) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.ctor()");
    initializeSoLoaderIfNecessary(applicationContext);

    DisplayMetricsHolder.initDisplayMetricsIfNotInitialized(applicationContext);

    mApplicationContext = applicationContext;
    mCurrentActivity = currentActivity;
    mDefaultBackButtonImpl = defaultHardwareBackBtnHandler;
    mBundleLoader = bundleLoader;
    mJSMainModuleName = jsMainModuleName;
    mPackages = packages;
    mUseDeveloperSupport = useDeveloperSupport;
    mDevSupportManager = DevSupportManagerFactory.create(
        applicationContext,
        mDevInterface,
        mJSMainModuleName,
        useDeveloperSupport,
        redBoxHandler,
        minNumShakes);
    mBridgeIdleDebugListener = bridgeIdleDebugListener;
    mLifecycleState = initialLifecycleState;
    mUIImplementationProvider = uiImplementationProvider;
    mMemoryPressureRouter = new MemoryPressureRouter(applicationContext);
    mNativeModuleCallExceptionHandler = nativeModuleCallExceptionHandler;
    mJSCConfig = jscConfig;
    mLazyNativeModulesEnabled = lazyNativeModulesEnabled;
    mLazyViewManagersEnabled = lazyViewManagersEnabled;
    mSetupReactContextInBackgroundEnabled = setupReactContextInBackgroundEnabled;
    mUseSeparateUIBackgroundThread = useSeparateUIBackgroundThread;
    mMinNumShakes = minNumShakes;

    // Instantiate ReactChoreographer in UI thread.
    ReactChoreographer.initialize();
  }

  public DevSupportManager getDevSupportManager() {
    return mDevSupportManager;
  }

  public MemoryPressureRouter getMemoryPressureRouter() {
    return mMemoryPressureRouter;
  }

  private static void initializeSoLoaderIfNecessary(Context applicationContext) {
    // Call SoLoader.initialize here, this is required for apps that does not use exopackage and
    // does not use SoLoader for loading other native code except from the one used by React Native
    // This way we don't need to require others to have additional initialization code and to
    // subclass android.app.Application.

    // Method SoLoader.init is idempotent, so if you wish to use native exopackage, just call
    // SoLoader.init with appropriate args before initializing ReactInstanceManager
    SoLoader.init(applicationContext, /* native exopackage */ false);
  }

  /**
   * Trigger react context initialization asynchronously in a background async task. This enables
   * applications to pre-load the application JS, and execute global code before
   * {@link ReactRootView} is available and measured. This should only be called the first time the
   * application is set up, which is enforced to keep developers from accidentally creating their
   * application multiple times without realizing it.
   *
   * Called from UI thread.
   */
  public void createReactContextInBackground() {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.createReactContextInBackground()");
    Assertions.assertCondition(
        !mHasStartedCreatingInitialContext,
        "createReactContextInBackground should only be called when creating the react " +
            "application for the first time. When reloading JS, e.g. from a new file, explicitly" +
            "use recreateReactContextInBackground");

    mHasStartedCreatingInitialContext = true;
    recreateReactContextInBackgroundInner();
  }

  /**
   * Recreate the react application and context. This should be called if configuration has
   * changed or the developer has requested the app to be reloaded. It should only be called after
   * an initial call to createReactContextInBackground.
   *
   * Called from UI thread.
   */
  @ThreadConfined(UI)
  public void recreateReactContextInBackground() {
    Assertions.assertCondition(
        mHasStartedCreatingInitialContext,
        "recreateReactContextInBackground should only be called after the initial " +
            "createReactContextInBackground call.");
    recreateReactContextInBackgroundInner();
  }

  @ThreadConfined(UI)
  private void recreateReactContextInBackgroundInner() {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.recreateReactContextInBackgroundInner()");
    UiThreadUtil.assertOnUiThread();

    if (mUseDeveloperSupport && mJSMainModuleName != null &&
      !Systrace.isTracing(TRACE_TAG_REACT_APPS | TRACE_TAG_REACT_JSC_CALLS)) {
      final DeveloperSettings devSettings = mDevSupportManager.getDevSettings();

      // If remote JS debugging is enabled, load from dev server.
      if (mDevSupportManager.hasUpToDateJSBundleInCache() &&
          !devSettings.isRemoteJSDebugEnabled()) {
        // If there is a up-to-date bundle downloaded from server,
        // with remote JS debugging disabled, always use that.
        onJSBundleLoadedFromServer();
      } else if (mBundleLoader == null) {
        mDevSupportManager.handleReloadJS();
      } else {
        mDevSupportManager.isPackagerRunning(
            new PackagerStatusCallback() {
              @Override
              public void onPackagerStatusFetched(final boolean packagerIsRunning) {
                UiThreadUtil.runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (packagerIsRunning) {
                          mDevSupportManager.handleReloadJS();
                        } else {
                          // If dev server is down, disable the remote JS debugging.
                          devSettings.setRemoteJSDebugEnabled(false);
                          recreateReactContextInBackgroundFromBundleLoader();
                        }
                      }
                    });
              }
            });
      }
      return;
    }

    recreateReactContextInBackgroundFromBundleLoader();
  }

  @ThreadConfined(UI)
  private void recreateReactContextInBackgroundFromBundleLoader() {
    Log.d(
      ReactConstants.TAG,
      "ReactInstanceManager.recreateReactContextInBackgroundFromBundleLoader()");
    recreateReactContextInBackground(
        new JSCJavaScriptExecutor.Factory(mJSCConfig.getConfigMap()),
        mBundleLoader);
  }

  /**
   * @return whether createReactContextInBackground has been called. Will return false after
   * onDestroy until a new initial context has been created.
   */
  public boolean hasStartedCreatingInitialContext() {
    return mHasStartedCreatingInitialContext;
  }

  /**
   * This method will give JS the opportunity to consume the back button event. If JS does not
   * consume the event, mDefaultBackButtonImpl will be invoked at the end of the round trip to JS.
   */
  public void onBackPressed() {
    UiThreadUtil.assertOnUiThread();
    ReactContext reactContext = mCurrentReactContext;
    if (mCurrentReactContext == null) {
      // Invoke without round trip to JS.
      FLog.w(ReactConstants.TAG, "Instance detached from instance manager");
      invokeDefaultOnBackPressed();
    } else {
      DeviceEventManagerModule deviceEventManagerModule =
          Assertions.assertNotNull(reactContext).getNativeModule(DeviceEventManagerModule.class);
      deviceEventManagerModule.emitHardwareBackPressed();
    }
  }

  private void invokeDefaultOnBackPressed() {
    UiThreadUtil.assertOnUiThread();
    if (mDefaultBackButtonImpl != null) {
      mDefaultBackButtonImpl.invokeDefaultOnBackPressed();
    }
  }

  /**
   * This method will give JS the opportunity to receive intents via Linking.
   */
  @ThreadConfined(UI)
  public void onNewIntent(Intent intent) {
    UiThreadUtil.assertOnUiThread();
    if (mCurrentReactContext == null) {
      FLog.w(ReactConstants.TAG, "Instance detached from instance manager");
    } else {
      String action = intent.getAction();
      Uri uri = intent.getData();

      if (Intent.ACTION_VIEW.equals(action) && uri != null) {
        DeviceEventManagerModule deviceEventManagerModule =
          Assertions.assertNotNull(mCurrentReactContext)
            .getNativeModule(DeviceEventManagerModule.class);
        deviceEventManagerModule.emitNewIntentReceived(uri);
      }

      mCurrentReactContext.onNewIntent(mCurrentActivity, intent);
    }
  }

  private void toggleElementInspector() {
    if (mCurrentReactContext != null) {
      mCurrentReactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit("toggleElementInspector", null);
    }
  }

  /**
   * Call this from {@link Activity#onPause()}. This notifies any listening modules so they can do
   * any necessary cleanup.
   *
   * @deprecated Use {@link #onHostPause(Activity)} instead.
   */
  @ThreadConfined(UI)
  public void onHostPause() {
    UiThreadUtil.assertOnUiThread();

    mDefaultBackButtonImpl = null;
    if (mUseDeveloperSupport) {
      mDevSupportManager.setDevSupportEnabled(false);
    }

    moveToBeforeResumeLifecycleState();
  }

  /**
   * Call this from {@link Activity#onPause()}. This notifies any listening modules so they can do
   * any necessary cleanup. The passed Activity is the current Activity being paused. This will
   * always be the foreground activity that would be returned by
   * {@link ReactContext#getCurrentActivity()}.
   *
   * @param activity the activity being paused
   */
  @ThreadConfined(UI)
  public void onHostPause(Activity activity) {
    Assertions.assertNotNull(mCurrentActivity);
    Assertions.assertCondition(
      activity == mCurrentActivity,
      "Pausing an activity that is not the current activity, this is incorrect! " +
        "Current activity: " + mCurrentActivity.getClass().getSimpleName() + " " +
        "Paused activity: " + activity.getClass().getSimpleName());
    onHostPause();
  }

  /**
   * Use this method when the activity resumes to enable invoking the back button directly from JS.
   *
   * This method retains an instance to provided mDefaultBackButtonImpl. Thus it's important to pass
   * from the activity instance that owns this particular instance of {@link
   * ReactInstanceManager}, so that once this instance receive {@link #onHostDestroy} event it
   * will clear the reference to that defaultBackButtonImpl.
   *
   * @param defaultBackButtonImpl a {@link DefaultHardwareBackBtnHandler} from an Activity that owns
   * this instance of {@link ReactInstanceManager}.
   */
  @ThreadConfined(UI)
  public void onHostResume(Activity activity, DefaultHardwareBackBtnHandler defaultBackButtonImpl) {
    UiThreadUtil.assertOnUiThread();

    mDefaultBackButtonImpl = defaultBackButtonImpl;
    if (mUseDeveloperSupport) {
      mDevSupportManager.setDevSupportEnabled(true);
    }

    mCurrentActivity = activity;
    moveToResumedLifecycleState(false);
  }

  /**
   * Call this from {@link Activity#onDestroy()}. This notifies any listening modules so they can do
   * any necessary cleanup.
   *
   * @deprecated use {@link #onHostDestroy(Activity)} instead
   */
  @ThreadConfined(UI)
  public void onHostDestroy() {
    UiThreadUtil.assertOnUiThread();

    if (mUseDeveloperSupport) {
      mDevSupportManager.setDevSupportEnabled(false);
    }

    moveToBeforeCreateLifecycleState();
    mCurrentActivity = null;
  }

  /**
   * Call this from {@link Activity#onDestroy()}. This notifies any listening modules so they can do
   * any necessary cleanup. If the activity being destroyed is not the current activity, no modules
   * are notified.
   *
   * @param activity the activity being destroyed
   */
  @ThreadConfined(UI)
  public void onHostDestroy(Activity activity) {
    if (activity == mCurrentActivity) {
      onHostDestroy();
    }
  }

  /**
   * Destroy this React instance and the attached JS context.
   */
  @ThreadConfined(UI)
  public void destroy() {
    UiThreadUtil.assertOnUiThread();

    if (mUseDeveloperSupport) {
      mDevSupportManager.setDevSupportEnabled(false);
    }

    moveToBeforeCreateLifecycleState();

    if (mCreateReactContextThread != null) {
      mCreateReactContextThread.interrupt();
      mCreateReactContextThread = null;
    }

    mMemoryPressureRouter.destroy(mApplicationContext);

    if (mCurrentReactContext != null) {
      mCurrentReactContext.destroy();
      mCurrentReactContext = null;
      mHasStartedCreatingInitialContext = false;
    }
    mCurrentActivity = null;

    ResourceDrawableIdHelper.getInstance().clear();
  }

  private synchronized void moveToResumedLifecycleState(boolean force) {
    if (mCurrentReactContext != null) {
      // we currently don't have an onCreate callback so we call onResume for both transitions
      if (force ||
          mLifecycleState == LifecycleState.BEFORE_RESUME ||
          mLifecycleState == LifecycleState.BEFORE_CREATE) {
        mCurrentReactContext.onHostResume(mCurrentActivity);
      }
    }
    mLifecycleState = LifecycleState.RESUMED;
  }

  private synchronized void moveToBeforeResumeLifecycleState() {
    if (mCurrentReactContext != null) {
      if (mLifecycleState == LifecycleState.BEFORE_CREATE) {
        mCurrentReactContext.onHostResume(mCurrentActivity);
        mCurrentReactContext.onHostPause();
      } else if (mLifecycleState == LifecycleState.RESUMED) {
        mCurrentReactContext.onHostPause();
      }
    }
    mLifecycleState = LifecycleState.BEFORE_RESUME;
  }

  private synchronized void moveToBeforeCreateLifecycleState() {
    if (mCurrentReactContext != null) {
      if (mLifecycleState == LifecycleState.RESUMED) {
        mCurrentReactContext.onHostPause();
        mLifecycleState = LifecycleState.BEFORE_RESUME;
      }
      if (mLifecycleState == LifecycleState.BEFORE_RESUME) {
        mCurrentReactContext.onHostDestroy();
      }
    }
    mLifecycleState = LifecycleState.BEFORE_CREATE;
  }

  private synchronized void moveReactContextToCurrentLifecycleState() {
    if (mLifecycleState == LifecycleState.RESUMED) {
      moveToResumedLifecycleState(true);
    }
  }

  @ThreadConfined(UI)
  public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
    if (mCurrentReactContext != null) {
      mCurrentReactContext.onActivityResult(activity, requestCode, resultCode, data);
    }
  }

  @ThreadConfined(UI)
  public void showDevOptionsDialog() {
    UiThreadUtil.assertOnUiThread();
    mDevSupportManager.showDevOptionsDialog();
  }

  /**
   * Attach given {@param rootView} to a catalyst instance manager and start JS application using
   * JS module provided by {@link ReactRootView#getJSModuleName}. If the react context is currently
   * being (re)-created, or if react context has not been created yet, the JS application associated
   * with the provided root view will be started asynchronously, i.e this method won't block.
   * This view will then be tracked by this manager and in case of catalyst instance restart it will
   * be re-attached.
   */
  @ThreadConfined(UI)
  public void attachRootView(ReactRootView rootView) {
    UiThreadUtil.assertOnUiThread();
    mAttachedRootViews.add(rootView);

    // Reset view content as it's going to be populated by the application content from JS.
    rootView.removeAllViews();
    rootView.setId(View.NO_ID);

    // If react context is being created in the background, JS application will be started
    // automatically when creation completes, as root view is part of the attached root view list.
    if (mCreateReactContextThread == null && mCurrentReactContext != null) {
      attachRootViewToInstance(rootView, mCurrentReactContext.getCatalystInstance());
    }
  }

  /**
   * Detach given {@param rootView} from current catalyst instance. It's safe to call this method
   * multiple times on the same {@param rootView} - in that case view will be detached with the
   * first call.
   */
  @ThreadConfined(UI)
  public void detachRootView(ReactRootView rootView) {
    UiThreadUtil.assertOnUiThread();
    if (mAttachedRootViews.remove(rootView)) {
      if (mCurrentReactContext != null && mCurrentReactContext.hasActiveCatalystInstance()) {
        detachViewFromInstance(rootView, mCurrentReactContext.getCatalystInstance());
      }
    }
  }

  /**
   * Uses configured {@link ReactPackage} instances to create all view managers.
   */
  public List<ViewManager> createAllViewManagers(
      ReactApplicationContext catalystApplicationContext) {
    ReactMarker.logMarker(CREATE_VIEW_MANAGERS_START);
    Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "createAllViewManagers");
    try {
      List<ViewManager> allViewManagers = new ArrayList<>();
      for (ReactPackage reactPackage : mPackages) {
        allViewManagers.addAll(reactPackage.createViewManagers(catalystApplicationContext));
      }
      return allViewManagers;
    } finally {
      Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
      ReactMarker.logMarker(CREATE_VIEW_MANAGERS_END);
    }
  }

  /**
   * Add a listener to be notified of react instance events.
   */
  public void addReactInstanceEventListener(ReactInstanceEventListener listener) {
    mReactInstanceEventListeners.add(listener);
  }

  /**
   * Remove a listener previously added with {@link #addReactInstanceEventListener}.
   */
  public void removeReactInstanceEventListener(ReactInstanceEventListener listener) {
    mReactInstanceEventListeners.remove(listener);
  }

  @VisibleForTesting
  public @Nullable ReactContext getCurrentReactContext() {
    return mCurrentReactContext;
  }

  public LifecycleState getLifecycleState() {
    return mLifecycleState;
  }

  public int getMinNumShakes() {
    return mMinNumShakes;
  }

  @ThreadConfined(UI)
  private void onReloadWithJSDebugger(JavaJSExecutor.Factory jsExecutorFactory) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.onReloadWithJSDebugger()");
    recreateReactContextInBackground(
        new ProxyJavaScriptExecutor.Factory(jsExecutorFactory),
        JSBundleLoader.createRemoteDebuggerBundleLoader(
            mDevSupportManager.getJSBundleURLForRemoteDebugging(),
            mDevSupportManager.getSourceUrl()));
  }

  @ThreadConfined(UI)
  private void onJSBundleLoadedFromServer() {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.onJSBundleLoadedFromServer()");
    recreateReactContextInBackground(
        new JSCJavaScriptExecutor.Factory(mJSCConfig.getConfigMap()),
        JSBundleLoader.createCachedBundleFromNetworkLoader(
            mDevSupportManager.getSourceUrl(),
            mDevSupportManager.getDownloadedJSBundleFile()));
  }

  @ThreadConfined(UI)
  private void recreateReactContextInBackground(
    JavaScriptExecutor.Factory jsExecutorFactory,
    JSBundleLoader jsBundleLoader) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.recreateReactContextInBackground()");
    UiThreadUtil.assertOnUiThread();

    final ReactContextInitParams initParams = new ReactContextInitParams(
      jsExecutorFactory,
      jsBundleLoader);
    if (mCreateReactContextThread == null) {
      runCreateReactContextOnNewThread(initParams);
    } else {
      mPendingReactContextInitParams = initParams;
    }
  }

  @ThreadConfined(UI)
  private void runCreateReactContextOnNewThread(final ReactContextInitParams initParams) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.runCreateReactContextOnNewThread()");
    UiThreadUtil.assertOnUiThread();
    if (mCurrentReactContext != null) {
      tearDownReactContext(mCurrentReactContext);
      mCurrentReactContext = null;
    }

    mCreateReactContextThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
          final ReactApplicationContext reactApplicationContext = createReactContext(
            initParams.getJsExecutorFactory().create(),
            initParams.getJsBundleLoader());

          if (mSetupReactContextInBackgroundEnabled) {
            mCreateReactContextThread = null;
          }
          ReactMarker.logMarker(PRE_SETUP_REACT_CONTEXT_START);
          final Runnable maybeRecreateReactContextRunnable = new Runnable() {
            @Override
            public void run() {
              if (mPendingReactContextInitParams != null) {
                runCreateReactContextOnNewThread(mPendingReactContextInitParams);
                mPendingReactContextInitParams = null;
              }
            }
          };
          Runnable setupReactContextRunnable = new Runnable() {
            @Override
            public void run() {
              if (!mSetupReactContextInBackgroundEnabled) {
                mCreateReactContextThread = null;
              }

              try {
                setupReactContext(reactApplicationContext);

                if (!mSetupReactContextInBackgroundEnabled) {
                  maybeRecreateReactContextRunnable.run();
                }
              } catch (Exception e) {
                mDevSupportManager.handleException(e);
              }
            }
          };

          if (mSetupReactContextInBackgroundEnabled) {
            reactApplicationContext.runOnNativeModulesQueueThread(setupReactContextRunnable);
            UiThreadUtil.runOnUiThread(maybeRecreateReactContextRunnable);
          } else {
            UiThreadUtil.runOnUiThread(setupReactContextRunnable);
          }
        } catch (Exception e) {
          mDevSupportManager.handleException(e);
        }
      }
    });
    mCreateReactContextThread.start();
  }

  private void setupReactContext(ReactApplicationContext reactContext) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.setupReactContext()");
    ReactMarker.logMarker(PRE_SETUP_REACT_CONTEXT_END);
    ReactMarker.logMarker(SETUP_REACT_CONTEXT_START);
    Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "setupReactContext");
    if (!mSetupReactContextInBackgroundEnabled) {
      UiThreadUtil.assertOnUiThread();
    }
    Assertions.assertCondition(mCurrentReactContext == null);
    mCurrentReactContext = Assertions.assertNotNull(reactContext);
    CatalystInstance catalystInstance =
      Assertions.assertNotNull(reactContext.getCatalystInstance());

    catalystInstance.initialize();
    mDevSupportManager.onNewReactContextCreated(reactContext);
    mMemoryPressureRouter.addMemoryPressureListener(catalystInstance);
    moveReactContextToCurrentLifecycleState();

    ReactMarker.logMarker(ATTACH_MEASURED_ROOT_VIEWS_START);
    synchronized (mAttachedRootViews) {
      for (ReactRootView rootView : mAttachedRootViews) {
        attachRootViewToInstance(rootView, catalystInstance);
      }
    }
    ReactMarker.logMarker(ATTACH_MEASURED_ROOT_VIEWS_END);

    ReactInstanceEventListener[] listeners =
      new ReactInstanceEventListener[mReactInstanceEventListeners.size()];
    listeners = mReactInstanceEventListeners.toArray(listeners);

    for (ReactInstanceEventListener listener : listeners) {
      listener.onReactContextInitialized(reactContext);
    }
    Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
    ReactMarker.logMarker(SETUP_REACT_CONTEXT_END);
    mCurrentReactContext.runOnJSQueueThread(new Runnable() {
      @Override
      public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
      }
    });
    mCurrentReactContext.runOnNativeModulesQueueThread(new Runnable() {
      @Override
      public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
      }
    });
    if (mUseSeparateUIBackgroundThread) {
      mCurrentReactContext.runOnUiBackgroundQueueThread(new Runnable() {
        @Override
        public void run() {
          Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        }
      });
    }
  }

  private void attachRootViewToInstance(
      final ReactRootView rootView,
      CatalystInstance catalystInstance) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.attachRootViewToInstance()");
    Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "attachRootViewToInstance");
    if (!mSetupReactContextInBackgroundEnabled) {
      UiThreadUtil.assertOnUiThread();
    }

    UIManagerModule uiManagerModule = catalystInstance.getNativeModule(UIManagerModule.class);
    final int rootTag = uiManagerModule.addRootView(rootView);
    rootView.setRootViewTag(rootTag);
    rootView.runApplication();
    Systrace.beginAsyncSection(
      TRACE_TAG_REACT_JAVA_BRIDGE,
      "pre_rootView.onAttachedToReactInstance",
      rootTag);
    UiThreadUtil.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Systrace.endAsyncSection(
          TRACE_TAG_REACT_JAVA_BRIDGE,
          "pre_rootView.onAttachedToReactInstance",
          rootTag);
        rootView.onAttachedToReactInstance();
      }
    });
    Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
  }

  private void detachViewFromInstance(
      ReactRootView rootView,
      CatalystInstance catalystInstance) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.detachViewFromInstance()");
    UiThreadUtil.assertOnUiThread();
    catalystInstance.getJSModule(AppRegistry.class)
        .unmountApplicationComponentAtRootTag(rootView.getId());
  }

  private void tearDownReactContext(ReactContext reactContext) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.tearDownReactContext()");
    UiThreadUtil.assertOnUiThread();
    if (mLifecycleState == LifecycleState.RESUMED) {
      reactContext.onHostPause();
    }

    synchronized (mAttachedRootViews) {
      for (ReactRootView rootView : mAttachedRootViews) {
        rootView.removeAllViews();
        rootView.setId(View.NO_ID);
      }
    }

    reactContext.destroy();
    mDevSupportManager.onReactInstanceDestroyed(reactContext);
    mMemoryPressureRouter.removeMemoryPressureListener(reactContext.getCatalystInstance());
  }

  /**
   * @return instance of {@link ReactContext} configured a {@link CatalystInstance} set
   */
  private ReactApplicationContext createReactContext(
      JavaScriptExecutor jsExecutor,
      JSBundleLoader jsBundleLoader) {
    Log.d(ReactConstants.TAG, "ReactInstanceManager.createReactContext()");
    ReactMarker.logMarker(CREATE_REACT_CONTEXT_START);
    final ReactApplicationContext reactContext = new ReactApplicationContext(mApplicationContext);
    NativeModuleRegistryBuilder nativeModuleRegistryBuilder = new NativeModuleRegistryBuilder(
      reactContext,
      this,
      mLazyNativeModulesEnabled);
    JavaScriptModuleRegistry.Builder jsModulesBuilder = new JavaScriptModuleRegistry.Builder();
    if (mUseDeveloperSupport) {
      reactContext.setNativeModuleCallExceptionHandler(mDevSupportManager);
    }

    ReactMarker.logMarker(PROCESS_PACKAGES_START);
    Systrace.beginSection(
        TRACE_TAG_REACT_JAVA_BRIDGE,
        "createAndProcessCoreModulesPackage");
    try {
      CoreModulesPackage coreModulesPackage =
        new CoreModulesPackage(
          this,
          mBackBtnHandler,
          mUIImplementationProvider,
          mLazyViewManagersEnabled);
      processPackage(coreModulesPackage, nativeModuleRegistryBuilder, jsModulesBuilder);
    } finally {
      Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
    }

    // TODO(6818138): Solve use-case of native/js modules overriding
    for (ReactPackage reactPackage : mPackages) {
      Systrace.beginSection(
          TRACE_TAG_REACT_JAVA_BRIDGE,
          "createAndProcessCustomReactPackage");
      try {
        processPackage(reactPackage, nativeModuleRegistryBuilder, jsModulesBuilder);
      } finally {
        Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
      }
    }
    ReactMarker.logMarker(PROCESS_PACKAGES_END);

    ReactMarker.logMarker(BUILD_NATIVE_MODULE_REGISTRY_START);
    Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "buildNativeModuleRegistry");
    NativeModuleRegistry nativeModuleRegistry;
    try {
       nativeModuleRegistry = nativeModuleRegistryBuilder.build();
    } finally {
      Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
      ReactMarker.logMarker(BUILD_NATIVE_MODULE_REGISTRY_END);
    }

    NativeModuleCallExceptionHandler exceptionHandler = mNativeModuleCallExceptionHandler != null
        ? mNativeModuleCallExceptionHandler
        : mDevSupportManager;
    CatalystInstanceImpl.Builder catalystInstanceBuilder = new CatalystInstanceImpl.Builder()
      .setReactQueueConfigurationSpec(mUseSeparateUIBackgroundThread ?
        ReactQueueConfigurationSpec.createWithSeparateUIBackgroundThread() :
        ReactQueueConfigurationSpec.createDefault())
      .setJSExecutor(jsExecutor)
      .setRegistry(nativeModuleRegistry)
      .setJSModuleRegistry(jsModulesBuilder.build())
      .setJSBundleLoader(jsBundleLoader)
      .setNativeModuleCallExceptionHandler(exceptionHandler);

    ReactMarker.logMarker(CREATE_CATALYST_INSTANCE_START);
    // CREATE_CATALYST_INSTANCE_END is in JSCExecutor.cpp
    Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "createCatalystInstance");
    final CatalystInstance catalystInstance;
    try {
      catalystInstance = catalystInstanceBuilder.build();
    } finally {
      Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
      ReactMarker.logMarker(CREATE_CATALYST_INSTANCE_END);
    }

    if (mBridgeIdleDebugListener != null) {
      catalystInstance.addBridgeIdleDebugListener(mBridgeIdleDebugListener);
    }
    if (Systrace.isTracing(TRACE_TAG_REACT_APPS | TRACE_TAG_REACT_JSC_CALLS)) {
      catalystInstance.setGlobalVariable("__RCTProfileIsProfiling", "true");
    }

    reactContext.initializeWithInstance(catalystInstance);
    ReactMarker.logMarker(ReactMarkerConstants.PRE_RUN_JS_BUNDLE_START);
    catalystInstance.runJSBundle();

    return reactContext;
  }

  private void processPackage(
    ReactPackage reactPackage,
    NativeModuleRegistryBuilder nativeModuleRegistryBuilder,
    JavaScriptModuleRegistry.Builder jsModulesBuilder) {
    SystraceMessage.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "processPackage")
      .arg("className", reactPackage.getClass().getSimpleName())
      .flush();
    if (reactPackage instanceof ReactPackageLogger) {
      ((ReactPackageLogger) reactPackage).startProcessPackage();
    }
    nativeModuleRegistryBuilder.processPackage(reactPackage);

    for (Class<? extends JavaScriptModule> jsModuleClass : reactPackage.createJSModules()) {
      jsModulesBuilder.add(jsModuleClass);
    }
    if (reactPackage instanceof ReactPackageLogger) {
      ((ReactPackageLogger) reactPackage).endProcessPackage();
    }
    SystraceMessage.endSection(TRACE_TAG_REACT_JAVA_BRIDGE).flush();
  }
}
