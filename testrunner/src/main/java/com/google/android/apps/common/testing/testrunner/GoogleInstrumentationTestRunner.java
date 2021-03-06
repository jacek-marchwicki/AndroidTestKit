// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.android.apps.common.testing.testrunner;


import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.test.AndroidTestRunner;
import android.test.InstrumentationTestRunner;
import android.test.TestSuiteProvider;
import android.util.Log;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import junit.framework.TestSuite;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Custom instrumentation runner for google android test cases.
 *
 */
public class
GoogleInstrumentationTestRunner
  extends
GoogleInstrumentation
    implements TestSuiteProvider {

  private static final long MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP = TimeUnit.SECONDS.toMillis(2);
  private static final String LOG_TAG = "GoogleInstrTest";
  private BridgeTestRunner bridgeTestRunner = new BridgeTestRunner();


  @Override
  public void finish(int resultCode, Bundle results) {

    try {
      UsageTrackerRegistry.getInstance().sendUsages();
    } catch (RuntimeException re) {
      Log.w(LOG_TAG, "Failed to send analytics.", re);
    }
    super.finish(resultCode, results);
  }

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    mockitoWorkarounds();

    String disableAnalyticsStringValue = arguments.getString(
        "disableAnalytics");
    boolean disableAnalytics = Boolean.parseBoolean(disableAnalyticsStringValue);

    if (!disableAnalytics) {
      UsageTracker tracker = new AnalyticsBasedUsageTracker.Builder(
          getTargetContext()).buildIfPossible();

      if (null != tracker) {
        UsageTrackerRegistry.registerInstance(tracker);
      }
    }

    Log.i(LOG_TAG, "Test Started!");


    // bridge will call start()
    bridgeTestRunner.onCreate(arguments);
  }


  @Override
  public TestSuite getTestSuite() {
    return bridgeTestRunner.getTestSuite();
  }

  @Override
  public void start() {
    List<TestCase> testCases = bridgeTestRunner.getAndroidTestRunner().getTestCases();

    // Register a listener to update the current test description.
    bridgeTestRunner.getAndroidTestRunner().addTestListener(new TestListener() {
      @Override
      public void startTest(Test test) {
        runOnMainSync(new ActivityFinisher());
      }

      @Override
      public void endTest(Test test) {
      }

      @Override
      public void addFailure(Test test, AssertionFailedError ae) {
      }

      @Override
      public void addError(Test test, Throwable t) {
      }
    });
    super.start();
  }


  @Override
  public void onStart() {
    // let the parent bring the app to a sane state.
    super.onStart();
    UsageTrackerRegistry.getInstance().trackUsage("TestRunner");



    try {
      // actually run tests!
      bridgeTestRunner.onStart();
    } finally {
    }
  }

  private void mockitoWorkarounds() {
    workaroundForMockitoOnEclair();
    specifyDexMakerCacheProperty();
  }

  private void specifyDexMakerCacheProperty() {
    // DexMaker uses heuristics to figure out where to store its temporary dex files
    // these heuristics may break (eg - they no longer work on JB MR2). So we create
    // our own cache dir to be used if the app doesnt specify a cache dir, rather then
    // relying on heuristics.
    //

    File dexCache = getTargetContext().getDir("dxmaker_cache", Context.MODE_PRIVATE);
    System.getProperties().put("dexmaker.dexcache", dexCache.getAbsolutePath());
  }

  /**
   * Enables the use of Mockito on Eclair (and below?).
   */
  private static void workaroundForMockitoOnEclair() {
    // This is a workaround for Eclair for http://code.google.com/p/mockito/issues/detail?id=354.
    // Mockito loads the Android-specific MockMaker (provided by DexMaker) using the current
    // thread's context ClassLoader. On Eclair this ClassLoader is set to the system ClassLoader
    // which doesn't know anything about this app (which includes DexMaker). The workaround is to
    // use the app's ClassLoader.
    // TODO(user): Remove this workaround once Eclair is no longer supported.

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ECLAIR_MR1) {
      return;
    }

    // Make Mockito look up a MockMaker using the app's ClassLoader, by asking Mockito to create
    // a mock of an interface (java.lang.Runnable).
    ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(
GoogleInstrumentationTestRunner.class.getClassLoader());

    // Since we don't require users of this class to use Mockito, we can only invoke Mockito via
    // the Reflection API.
    try {
      Class mockitoClass = Class.forName("org.mockito.Mockito");
      try {
        // Invoke org.mockito.Mockito.mock(Runnable.class)
        mockitoClass.getMethod("mock", Class.class).invoke(null, Runnable.class);
      } catch (Exception e) {
        throw new RuntimeException("Workaround for Mockito on Eclair and below failed", e);
      }
    } catch (ClassNotFoundException ignored) {
      // Mockito not present -- no need to do anything
    } finally {
      Thread.currentThread().setContextClassLoader(originalContextClassLoader);
    }
  }

  /**
   * Bridge that allows us to use the argument processing / awareness of stock
   * InstrumentationTestRunner along side the seperate inheritance hierarchy of
   * GoogleInstrumentation(and TestRunner).
   *
   * This is regrettable but android's ITR is not very extension friendly.
   * You may have to add additional method bridging in the future.
   */
  private class BridgeTestRunner extends InstrumentationTestRunner {
    private AndroidTestRunner myAndroidTestRunner = new AndroidTestRunner() {
        @Override
        public void setInstrumentation(Instrumentation instr) {
super.setInstrumentation(GoogleInstrumentationTestRunner.this);
        }

        @Override
        public void setInstrumentaiton(Instrumentation instr) {
super.setInstrumentation(GoogleInstrumentationTestRunner.this);
        }

    };

    @Override
    public Context getTargetContext() {
return GoogleInstrumentationTestRunner.this.getTargetContext();
    }

    @Override
    public Context getContext() {
return GoogleInstrumentationTestRunner.this.getContext();
    }

    @Override
    public void start()  {
GoogleInstrumentationTestRunner.this.start();
    }

    @Override
    public AndroidTestRunner getAndroidTestRunner() {
      return myAndroidTestRunner;
    }

    @Override
    public void sendStatus(int resultCode, Bundle results) {
GoogleInstrumentationTestRunner.this.sendStatus(resultCode, results);
    }

    @Override
    public void finish(int resultCode, Bundle results) {
GoogleInstrumentationTestRunner.this.finish(resultCode, results);
    }
  }

}
