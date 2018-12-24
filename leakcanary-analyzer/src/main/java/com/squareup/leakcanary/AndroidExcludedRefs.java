/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import java.lang.ref.PhantomReference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.EnumSet;

/**
 * This class is a work in progress. You can help by reporting leak traces that seem to be caused
 * by the Android SDK, here: https://github.com/square/leakcanary/issues/new
 *
 * We filter on SDK versions and Manufacturers because many of those leaks are specific to a given
 * manufacturer implementation, they usually share their builds across multiple models, and the
 * leaks eventually get fixed in newer versions.
 *
 * Most app developers should use {@link #createAppDefaults()}. However, you can also pick the
 * leaks you want to ignore by creating an {@link EnumSet} that matches your needs and calling
 * {@link #createBuilder(EnumSet)}
 */
@SuppressWarnings({ "unused", "WeakerAccess" }) // Public API.
public enum AndroidExcludedRefs {

  // ######## Android SDK Excluded refs ########

  // ######## General Excluded refs ########

  SOFT_REFERENCES {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.clazz(WeakReference.class.getName()).alwaysExclude();
      excluded.clazz(SoftReference.class.getName()).alwaysExclude();
      excluded.clazz(PhantomReference.class.getName()).alwaysExclude();
      excluded.clazz("java.lang.ref.Finalizer").alwaysExclude();
      excluded.clazz("java.lang.ref.FinalizerReference").alwaysExclude();
      excluded.clazz("yph.deeptest.DeepTest").alwaysExclude();
      excluded.clazz("android.support.test.rule.ActivityTestRule").alwaysExclude();
    }
  },

  FINALIZER_WATCHDOG_DAEMON {
    @Override void add(ExcludedRefs.Builder excluded) {
      // If the FinalizerWatchdogDaemon thread is on the shortest path, then there was no other
      // reference to the object and it was about to be GCed.
      excluded.thread("FinalizerWatchdogDaemon").alwaysExclude();
    }
  },

  MAIN {
    @Override void add(ExcludedRefs.Builder excluded) {
      // The main thread stack is ever changing so local variables aren't likely to hold references
      // for long. If this is on the shortest path, it's probably that there's a longer path with
      // a real leak.
      excluded.thread("main").alwaysExclude();
    }
  },


  EVENT_RECEIVER__MMESSAGE_QUEUE {
    @Override void add(ExcludedRefs.Builder excluded) {
      //  DisplayEventReceiver keeps a reference message queue object so that it is not GC'd while
      // the native peer of the receiver is using them.
      // The main thread message queue is held on by the main Looper, but that might be a longer
      // path. Let's not confuse people with a shorter path that is less meaningful.
      excluded.instanceField("android.view.Choreographer$FrameDisplayEventReceiver",
          "mMessageQueue").alwaysExclude();
    }
  };

  /**
   * This returns the references in the leak path that can be ignored for app developers. This
   * doesn't mean there is no memory leak, to the contrary. However, some leaks are caused by bugs
   * in AOSP or manufacturer forks of AOSP. In such cases, there is very little we can do as app
   * developers except by resorting to serious hacks, so we remove the noise caused by those leaks.
   */
  public static ExcludedRefs.Builder createAppDefaults() {
    return createBuilder(EnumSet.allOf(AndroidExcludedRefs.class));
  }

  public static ExcludedRefs.Builder createBuilder(EnumSet<AndroidExcludedRefs> refs) {
    ExcludedRefs.Builder excluded = ExcludedRefs.builder();
    for (AndroidExcludedRefs ref : refs) {
      if (ref.applies) {
        ref.add(excluded);
        ((ExcludedRefs.BuilderWithParams) excluded).named(ref.name());
      }
    }
    return excluded;
  }

  final boolean applies;

  AndroidExcludedRefs() {
    this(true);
  }

  AndroidExcludedRefs(boolean applies) {
    this.applies = applies;
  }

  abstract void add(ExcludedRefs.Builder excluded);
}
