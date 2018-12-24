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

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Field;
import com.squareup.haha.perflib.HprofParser;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.RootObj;
import com.squareup.haha.perflib.RootType;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.Type;
import com.squareup.haha.perflib.io.HprofBuffer;
import com.squareup.haha.perflib.io.MemoryMappedFileBuffer;
import com.squareup.haha.trove.THashMap;
import com.squareup.haha.trove.TObjectProcedure;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.squareup.leakcanary.HahaHelper.classInstanceValues;
import static com.squareup.leakcanary.HahaHelper.extendsThread;
import static com.squareup.leakcanary.HahaHelper.fieldValue;
import static com.squareup.leakcanary.HahaHelper.threadName;
import static com.squareup.leakcanary.LeakTraceElement.Holder.ARRAY;
import static com.squareup.leakcanary.LeakTraceElement.Holder.CLASS;
import static com.squareup.leakcanary.LeakTraceElement.Holder.OBJECT;
import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.LeakTraceElement.Type.ARRAY_ENTRY;
import static com.squareup.leakcanary.LeakTraceElement.Type.INSTANCE_FIELD;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public final class HeapAnalyzer {

    private static final String ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$";

    /**
     * @param hprofPath hprof路径
     * @param findLeak 是否只查找泄露对象
    * */
    public void checkForLeak(String hprofPath, boolean findLeak, boolean findBitmap) {
        File heapDumpFile = new File(hprofPath);
        if (!heapDumpFile.exists()) {
            Log.e("File does not exist: " + heapDumpFile);
        }
        try {
            HprofBuffer buffer = new MemoryMappedFileBuffer(heapDumpFile);
            HprofParser parser = new HprofParser(buffer);
            Snapshot snapshot = parser.parse();
            deduplicateGcRoots(snapshot);//去重

            findLeakTrace(snapshot, findLeak, findBitmap);
        } catch (IOException e) {
            Log.e(e.toString());
        }
    }

    /**
     * Pruning duplicates reduces memory pressure from hprof bloat added in Marshmallow.
     */
    void deduplicateGcRoots(Snapshot snapshot) {
        // THashMap has a smaller memory footprint than HashMap.
        final THashMap<String, RootObj> uniqueRootMap = new THashMap<>();

        final Collection<RootObj> gcRoots = snapshot.getGCRoots();
        for (RootObj root : gcRoots) {
            String key = generateRootKey(root);
            if (!uniqueRootMap.containsKey(key)) {
                uniqueRootMap.put(key, root);
            }
        }

        // Repopulate snapshot with unique GC roots.
        gcRoots.clear();
        uniqueRootMap.forEach(new TObjectProcedure<String>() {
            @Override
            public boolean execute(String key) {
                return gcRoots.add(uniqueRootMap.get(key));
            }
        });
    }

    private String generateRootKey(RootObj root) {
        return String.format("%s@0x%08x", root.getRootType().getName(), root.getId());
    }


    private void findLeakTrace(Snapshot snapshot, boolean findLeak, boolean findBitmap) {
        ShortestPathFinder pathFinder = new ShortestPathFinder(AndroidExcludedRefs.createAppDefaults().build());
        List<LeakTrace> traceList = pathFinder.findPath(snapshot, this, findLeak, findBitmap);
        for (LeakTrace leakTrace : traceList) {
            System.out.println(leakTrace.toString() + "num= " + leakTrace.appearNum +
                    (leakTrace.bmpAddress.isEmpty() ? "" : "  bmpAddress= " +leakTrace.bmpAddress.size() + leakTrace.bmpAddress) + "\n");
        }
    }

    /**
     * Bitmaps and bitmap byte arrays are sometimes held by native gc roots, so they aren't included
     * in the retained size because their root dominator is a native gc root.
     * To fix this, we check if the leaking instance is a dominator for each bitmap instance and then
     * add the bitmap size.
     * <p>
     * From experience, we've found that bitmap created in code (Bitmap.createBitmap()) are correctly
     * accounted for, however bitmaps set in layouts are not.
     */
    private long computeIgnoredBitmapRetainedSize(Snapshot snapshot, Instance leakingInstance) {
        long bitmapRetainedSize = 0;
        ClassObj bitmapClass = snapshot.findClass("android.graphics.Bitmap");

        for (Instance bitmapInstance : bitmapClass.getInstancesList()) {
            if (isIgnoredDominator(leakingInstance, bitmapInstance)) {
                ArrayInstance mBufferInstance = fieldValue(classInstanceValues(bitmapInstance), "mBuffer");
                // Native bitmaps have mBuffer set to null. We sadly can't account for them.
                if (mBufferInstance == null) {
                    continue;
                }
                long bufferSize = mBufferInstance.getTotalRetainedSize();
                long bitmapSize = bitmapInstance.getTotalRetainedSize();
                // Sometimes the size of the buffer isn't accounted for in the bitmap retained size. Since
                // the buffer is large, it's easy to detect by checking for bitmap size < buffer size.
                if (bitmapSize < bufferSize) {
                    bitmapSize += bufferSize;
                }
                bitmapRetainedSize += bitmapSize;
            }
        }
        return bitmapRetainedSize;
    }

    private boolean isIgnoredDominator(Instance dominator, Instance instance) {
        boolean foundNativeRoot = false;
        while (true) {
            Instance immediateDominator = instance.getImmediateDominator();
            if (immediateDominator instanceof RootObj
                    && ((RootObj) immediateDominator).getRootType() == RootType.UNKNOWN) {
                // Ignore native roots
                instance = instance.getNextInstanceToGcRoot();
                foundNativeRoot = true;
            } else {
                instance = immediateDominator;
            }
            if (instance == null) {
                return false;
            }
            if (instance == dominator) {
                return foundNativeRoot;
            }
        }
    }

    public LeakTrace buildLeakTrace(LeakNode leakingNode) {
        List<LeakTraceElement> elements = new ArrayList<>();
        // We iterate from the leak to the GC root
        LeakNode node = new LeakNode(null, null, leakingNode, null);
        while (node != null) {
            LeakTraceElement element = buildLeakElement(node);
            if (element != null) {
                elements.add(0, element);
            }
            node = node.parent;
        }
        return new LeakTrace(elements);
    }

    private LeakTraceElement buildLeakElement(LeakNode node) {
        if (node.parent == null) {
            // Ignore any root node.
            return null;
        }
        Instance holder = node.parent.instance;

        if (holder instanceof RootObj) {
            return null;
        }
        LeakTraceElement.Holder holderType;
        String className;
        String extra = null;
        List<LeakReference> leakReferences = describeFields(holder);

        className = getClassName(holder);

        List<String> classHierarchy = new ArrayList<>();
        classHierarchy.add(className);
        String rootClassName = Object.class.getName();
        if (holder instanceof ClassInstance) {
            ClassObj classObj = holder.getClassObj();
            while (classObj.getSuperClassObj() != null && !(classObj = classObj.getSuperClassObj()).getClassName().equals(rootClassName)) {
                classHierarchy.add(classObj.getClassName());
            }
        }

        if (holder instanceof ClassObj) {
            holderType = CLASS;
        } else if (holder instanceof ArrayInstance) {
            holderType = ARRAY;
        } else {
            ClassObj classObj = holder.getClassObj();
            if (extendsThread(classObj)) {
                holderType = THREAD;
                String threadName = threadName(holder);
                extra = "(named '" + threadName + "')";
            } else if (className.matches(ANONYMOUS_CLASS_NAME_PATTERN)) {
                String parentClassName = classObj.getSuperClassObj().getClassName();
                if (rootClassName.equals(parentClassName)) {
                    holderType = OBJECT;
                    try {
                        // This is an anonymous class implementing an interface. The API does not give access
                        // to the interfaces implemented by the class. We check if it's in the class path and
                        // use that instead.
                        Class<?> actualClass = Class.forName(classObj.getClassName());
                        Class<?>[] interfaces = actualClass.getInterfaces();
                        if (interfaces.length > 0) {
                            Class<?> implementedInterface = interfaces[0];
                            extra = "(anonymous implementation of " + implementedInterface.getName() + ")";
                        } else {
                            extra = "(anonymous subclass of java.lang.Object)";
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                } else {
                    holderType = OBJECT;
                    // Makes it easier to figure out which anonymous class we're looking at.
                    extra = "(anonymous subclass of " + parentClassName + ")";
                }
            } else {
                holderType = OBJECT;
            }
        }
        return new LeakTraceElement(node.leakReference, holderType, classHierarchy, extra,
                node.exclusion, leakReferences);
    }

    private List<LeakReference> describeFields(Instance instance) {
        List<LeakReference> leakReferences = new ArrayList<>();

        if (instance instanceof ClassObj) {
            ClassObj classObj = (ClassObj) instance;
            for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
                String name = entry.getKey().getName();
                String value = entry.getValue() == null ? "null" : entry.getValue().toString();
                leakReferences.add(new LeakReference(STATIC_FIELD, name, value));
            }
        } else if (instance instanceof ArrayInstance) {
            ArrayInstance arrayInstance = (ArrayInstance) instance;
            if (arrayInstance.getArrayType() == Type.OBJECT) {
                Object[] values = arrayInstance.getValues();
                for (int i = 0; i < values.length; i++) {
                    String name = Integer.toString(i);
                    String value = values[i] == null ? "null" : values[i].toString();
                    leakReferences.add(new LeakReference(ARRAY_ENTRY, name, value));
                }
            }
        } else {
            ClassObj classObj = instance.getClassObj();
            for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
                String name = entry.getKey().getName();
                String value = entry.getValue() == null ? "null" : entry.getValue().toString();
                leakReferences.add(new LeakReference(STATIC_FIELD, name, value));
            }
            ClassInstance classInstance = (ClassInstance) instance;
            for (ClassInstance.FieldValue field : classInstance.getValues()) {
                String name = field.getField().getName();
                String value = field.getValue() == null ? "null" : field.getValue().toString();
                leakReferences.add(new LeakReference(INSTANCE_FIELD, name, value));
            }
        }
        return leakReferences;
    }

    private String getClassName(Instance instance) {
        String className;
        if (instance instanceof ClassObj) {
            ClassObj classObj = (ClassObj) instance;
            className = classObj.getClassName();
        } else if (instance instanceof ArrayInstance) {
            ArrayInstance arrayInstance = (ArrayInstance) instance;
            className = arrayInstance.getClassObj().getClassName();
        } else {
            ClassObj classObj = instance.getClassObj();
            className = classObj.getClassName();
        }
        return className;
    }

    private long since(long analysisStartNanoTime) {
        return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime);
    }
}
