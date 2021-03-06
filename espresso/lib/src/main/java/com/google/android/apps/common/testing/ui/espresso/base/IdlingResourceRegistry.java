package com.google.android.apps.common.testing.ui.espresso.base;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.android.apps.common.testing.ui.espresso.IdlingResource;
import com.google.android.apps.common.testing.ui.espresso.IdlingResource.ResourceCallback;
import com.google.common.collect.Lists;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps track of user-registered {@link IdlingResource}s.
 */
@Singleton
public final class IdlingResourceRegistry {
  private static final String TAG = IdlingResourceRegistry.class.getSimpleName();

  private static final int DYNAMIC_RESOURCE_HAS_IDLED = 1;
  private static final int TIMEOUT_OCCURRED = 2;
  private static final int IDLE_WARNING_REACHED = 3;
  private static final int POSSIBLE_RACE_CONDITION_DETECTED = 4;

  private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(26);
  private static final long TIMEOUT_WARNING_INTERVAL = TimeUnit.SECONDS.toMillis(5);

  private static final IdleNotificationCallback NO_OP_CALLBACK = new IdleNotificationCallback() {

    @Override
    public void allResourcesIdle() {}

    @Override
    public void resourcesStillBusyWarning(List<String> busys) {}

    @Override
    public void resourcesHaveTimedOut(List<String> busys) {}
  };

  // resources and idleState should only be accessed on main thread
  private final List<IdlingResource> resources = Lists.newArrayList();
  // idleState.get(i) == true indicates resources.get(i) is idle, false indicates it's busy
  private final BitSet idleState = new BitSet();
  private final Looper looper;
  private final Handler handler;

  private IdleNotificationCallback idleNotificationCallback = NO_OP_CALLBACK;

  @Inject
  public IdlingResourceRegistry(Looper looper) {
    this.looper = looper;
    this.handler = new Handler(looper, new Dispatcher());
  }

  /**
   * Registers the given resource.
   */
  public void register(final IdlingResource resource) {
    checkNotNull(resource);
    if (Looper.myLooper() != looper) {
      handler.post(new Runnable() {
        @Override
        public void run() {
          register(resource);
        }
      });
    } else {
      resources.add(resource);
      final int position = resources.size() - 1;
      registerToIdleCallback(resource, position);
      idleState.set(position, resource.isIdleNow());
    }
  }

  private void registerToIdleCallback(IdlingResource resource, final int position) {
    resource.registerIdleTransitionCallback(new ResourceCallback() {
      @Override
      public void onTransitionToIdle() {
        Message m = handler.obtainMessage(DYNAMIC_RESOURCE_HAS_IDLED);
        m.arg1 = position;
        handler.sendMessage(m);
      }
    });
  }

  boolean allResourcesAreIdle() {
    checkState(Looper.myLooper() == looper);
    for (int i = idleState.nextSetBit(0); i >= 0 && i < resources.size();
        i = idleState.nextSetBit(i + 1)) {
      idleState.set(i, resources.get(i).isIdleNow());
    }
    return idleState.cardinality() == resources.size();
  }

  interface IdleNotificationCallback {
    public void allResourcesIdle();

    public void resourcesStillBusyWarning(List<String> busyResourceNames);

    public void resourcesHaveTimedOut(List<String> busyResourceNames);
  }

  void notifyWhenAllResourcesAreIdle(IdleNotificationCallback callback) {
    checkNotNull(callback);
    checkState(Looper.myLooper() == looper);
    checkState(idleNotificationCallback == NO_OP_CALLBACK, "Callback has already been registered.");
    if (allResourcesAreIdle()) {
      callback.allResourcesIdle();
    } else {
      idleNotificationCallback = callback;
      scheduleTimeoutMessages();
    }
  }

  private void scheduleTimeoutMessages() {
    Message timeoutWarning = handler.obtainMessage(IDLE_WARNING_REACHED);
    handler.sendMessageDelayed(timeoutWarning, TIMEOUT_WARNING_INTERVAL);
    Message timeoutError = handler.obtainMessage(TIMEOUT_OCCURRED);
    handler.sendMessageDelayed(timeoutError, TIMEOUT);
  }

  private List<String> getBusyResources() {
    List<String> busyResourceNames = Lists.newArrayList();
    List<Integer> racyResources = Lists.newArrayList();

    for (int i = 0; i < resources.size(); i++) {
      IdlingResource resource = resources.get(i);
      if (!idleState.get(i)) {
        if (resource.isIdleNow()) {
          // We have not been notified of a BUSY -> IDLE transition, but the resource is telling us
          // its that its idle. Either it's a race condition or is this resource buggy.
          racyResources.add(i);
        } else {
          busyResourceNames.add(resource.getName());
        }
      }
    }

    if (!racyResources.isEmpty()) {
      Message raceBuster = handler.obtainMessage(POSSIBLE_RACE_CONDITION_DETECTED);
      raceBuster.obj = racyResources;
      handler.sendMessage(raceBuster);
      return null;
    } else {
      return busyResourceNames;
    }
  }

  private class Dispatcher implements Handler.Callback {
    @Override
    public boolean handleMessage(Message m) {
      switch (m.what) {
        case DYNAMIC_RESOURCE_HAS_IDLED:
          handleResourceIdled(m);
          break;
        case IDLE_WARNING_REACHED:
          handleTimeoutWarning();
          break;
        case TIMEOUT_OCCURRED:
          handleTimeout();
          break;
        case POSSIBLE_RACE_CONDITION_DETECTED:
          handleRaceCondition(m);
          break;
        default:
          Log.w(TAG, "Unknown message type: " + m);
          return false;
      }
      return true;
    }

    private void handleResourceIdled(Message m) {
      idleState.set(m.arg1, true);
      if (idleState.cardinality() == resources.size()) {
        idleNotificationCallback.allResourcesIdle();
        deregister();
      }
    }

    private void handleTimeoutWarning() {
      List<String> busyResources = getBusyResources();
      if (busyResources == null) {
        // null indicates that there is either a race or a programming error
        // a race detector message has been inserted into the q.
        // reinsert the idle_warning_reached message into the q directly after it
        // so we generate warnings if the system is still sane.
        handler.sendEmptyMessage(IDLE_WARNING_REACHED);
      } else {
        idleNotificationCallback.resourcesStillBusyWarning(busyResources);
        handler.sendMessageDelayed(
            handler.obtainMessage(IDLE_WARNING_REACHED), TIMEOUT_WARNING_INTERVAL);
      }
    }

    private void handleTimeout() {
      List<String> busyResources = getBusyResources();
      if (busyResources == null) {
        // detected a possible race... we've enqueued a race busting message
        // so either that'll resolve the race or kill the app because it's buggy.
        // if the race resolves, we need to timeout properly.
        handler.sendEmptyMessage(TIMEOUT_OCCURRED);
      } else {
        idleNotificationCallback.resourcesHaveTimedOut(busyResources);
        deregister();
      }
    }

    @SuppressWarnings("unchecked")
    private void handleRaceCondition(Message m) {
      for (Integer i : (List<Integer>) m.obj) {
        if (idleState.get(i)) {
          // it was a race... i is now idle, everything is fine...
        } else {
          throw new IllegalStateException(String.format(
              "Resource %s isIdleNow() is returning true, but a message indicating that the "
              + "resource has transitioned from busy to idle was never sent.",
              resources.get(i).getName()));
        }
      }
    }

    private void deregister() {
      handler.removeMessages(IDLE_WARNING_REACHED);
      handler.removeMessages(TIMEOUT_OCCURRED);
      handler.removeMessages(POSSIBLE_RACE_CONDITION_DETECTED);
      idleNotificationCallback = NO_OP_CALLBACK;
    }
  }
}
