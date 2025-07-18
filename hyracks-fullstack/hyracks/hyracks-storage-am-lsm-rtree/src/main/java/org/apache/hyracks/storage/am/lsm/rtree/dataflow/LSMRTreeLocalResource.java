/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.storage.am.lsm.rtree.dataflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.hyracks.api.application.INCServiceContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.ILinearizeComparatorFactory;
import org.apache.hyracks.api.dataflow.value.ITypeTraits;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.io.FileReference;
import org.apache.hyracks.api.io.IIOManager;
import org.apache.hyracks.api.io.IJsonSerializable;
import org.apache.hyracks.api.io.IPersistedResourceRegistry;
import org.apache.hyracks.control.common.controllers.NCConfig;
import org.apache.hyracks.control.nc.NodeControllerService;
import org.apache.hyracks.storage.am.common.api.IMetadataPageManagerFactory;
import org.apache.hyracks.storage.am.common.api.INullIntrospector;
import org.apache.hyracks.storage.am.common.api.IPrimitiveValueProviderFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIOOperationCallbackFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIOOperationSchedulerProvider;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMMergePolicyFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMOperationTrackerFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMPageWriteCallbackFactory;
import org.apache.hyracks.storage.am.lsm.common.api.IVirtualBufferCache;
import org.apache.hyracks.storage.am.lsm.common.api.IVirtualBufferCacheProvider;
import org.apache.hyracks.storage.am.lsm.common.dataflow.LsmResource;
import org.apache.hyracks.storage.am.lsm.rtree.utils.LSMRTreeUtils;
import org.apache.hyracks.storage.am.rtree.frames.RTreePolicyType;
import org.apache.hyracks.storage.common.IIndex;
import org.apache.hyracks.storage.common.IStorageManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class LSMRTreeLocalResource extends LsmResource {

    private static final long serialVersionUID = 1L;

    protected final IBinaryComparatorFactory[] btreeCmpFactories;
    protected final IPrimitiveValueProviderFactory[] valueProviderFactories;
    protected final RTreePolicyType rtreePolicyType;
    protected final ILinearizeComparatorFactory linearizeCmpFactory;
    protected final int[] rtreeFields;
    protected final int[] buddyBTreeFields;
    protected final boolean isPointMBR;
    protected final double bloomFilterFalsePositiveRate;

    public LSMRTreeLocalResource(String path, IStorageManager storageManager, ITypeTraits[] typeTraits,
            IBinaryComparatorFactory[] rtreeCmpFactories, ITypeTraits[] filterTypeTraits,
            IBinaryComparatorFactory[] filterCmpFactories, int[] filterFields,
            ILSMOperationTrackerFactory opTrackerProvider, ILSMIOOperationCallbackFactory ioOpCallbackFactory,
            ILSMPageWriteCallbackFactory pageWriteCallbackFactory,
            IMetadataPageManagerFactory metadataPageManagerFactory, IVirtualBufferCacheProvider vbcProvider,
            ILSMIOOperationSchedulerProvider ioSchedulerProvider, ILSMMergePolicyFactory mergePolicyFactory,
            Map<String, String> mergePolicyProperties, boolean durable, IBinaryComparatorFactory[] btreeCmpFactories,
            IPrimitiveValueProviderFactory[] valueProviderFactories, RTreePolicyType rtreePolicyType,
            ILinearizeComparatorFactory linearizeCmpFactory, int[] rtreeFields, int[] buddyBTreeFields,
            boolean isPointMBR, double bloomFilterFalsePositiveRate, ITypeTraits nullTypeTraits,
            INullIntrospector nullIntrospector) {
        super(path, storageManager, typeTraits, rtreeCmpFactories, filterTypeTraits, filterCmpFactories, filterFields,
                opTrackerProvider, ioOpCallbackFactory, pageWriteCallbackFactory, metadataPageManagerFactory,
                vbcProvider, ioSchedulerProvider, mergePolicyFactory, mergePolicyProperties, durable, nullTypeTraits,
                nullIntrospector);
        this.btreeCmpFactories = btreeCmpFactories;
        this.valueProviderFactories = valueProviderFactories;
        this.rtreePolicyType = rtreePolicyType;
        this.linearizeCmpFactory = linearizeCmpFactory;
        this.rtreeFields = rtreeFields;
        this.buddyBTreeFields = buddyBTreeFields;
        this.isPointMBR = isPointMBR;
        this.bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate;
    }

    protected LSMRTreeLocalResource(IPersistedResourceRegistry registry, JsonNode json,
            IBinaryComparatorFactory[] btreeCmpFactories, IPrimitiveValueProviderFactory[] valueProviderFactories,
            RTreePolicyType rtreePolicyType, ILinearizeComparatorFactory linearizeCmpFactory, int[] rtreeFields,
            int[] buddyBTreeFields, boolean isPointMBR, double bloomFilterFalsePositiveRate)
            throws HyracksDataException {
        super(registry, json);
        this.btreeCmpFactories = btreeCmpFactories;
        this.valueProviderFactories = valueProviderFactories;
        this.rtreePolicyType = rtreePolicyType;
        this.linearizeCmpFactory = linearizeCmpFactory;
        this.rtreeFields = rtreeFields;
        this.buddyBTreeFields = buddyBTreeFields;
        this.isPointMBR = isPointMBR;
        this.bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate;
    }

    @Override
    public IIndex createInstance(INCServiceContext ncServiceCtx) throws HyracksDataException {
        IIOManager ioManager = storageManager.getIoManager(ncServiceCtx);
        NCConfig storageConfig = ((NodeControllerService) ncServiceCtx.getControllerService()).getConfiguration();
        FileReference fileRef = ioManager.resolve(path);
        List<IVirtualBufferCache> virtualBufferCaches = vbcProvider.getVirtualBufferCaches(ncServiceCtx, fileRef);
        ioOpCallbackFactory.initialize(ncServiceCtx, this);
        pageWriteCallbackFactory.initialize(ncServiceCtx, this);
        return LSMRTreeUtils.createLSMTree(storageConfig, ioManager, virtualBufferCaches, fileRef,
                storageManager.getBufferCache(ncServiceCtx), typeTraits, cmpFactories, btreeCmpFactories,
                valueProviderFactories, rtreePolicyType, bloomFilterFalsePositiveRate,
                mergePolicyFactory.createMergePolicy(mergePolicyProperties, ncServiceCtx),
                opTrackerProvider.getOperationTracker(ncServiceCtx, this),
                ioSchedulerProvider.getIoScheduler(ncServiceCtx), ioOpCallbackFactory, pageWriteCallbackFactory,
                linearizeCmpFactory, rtreeFields, buddyBTreeFields, filterTypeTraits, filterCmpFactories, filterFields,
                durable, isPointMBR, metadataPageManagerFactory, nullTypeTraits, nullIntrospector);
    }

    @Override
    public JsonNode toJson(IPersistedResourceRegistry registry) throws HyracksDataException {
        ObjectNode jsonObject = registry.getClassIdentifier(getClass(), serialVersionUID);
        super.appendToJson(jsonObject, registry);
        return jsonObject;
    }

    @Override
    protected void appendToJson(final ObjectNode json, IPersistedResourceRegistry registry)
            throws HyracksDataException {
        super.appendToJson(json, registry);
        final ArrayNode btreeCmpFactoriesArray = OBJECT_MAPPER.createArrayNode();
        for (IBinaryComparatorFactory factory : btreeCmpFactories) {
            btreeCmpFactoriesArray.add(factory.toJson(registry));
        }
        json.set("btreeCmpFactories", btreeCmpFactoriesArray);
        json.set("linearizeCmpFactory", linearizeCmpFactory.toJson(registry));
        final ArrayNode valueProviderFactoriesArray = OBJECT_MAPPER.createArrayNode();
        for (IPrimitiveValueProviderFactory factory : valueProviderFactories) {
            valueProviderFactoriesArray.add(factory.toJson(registry));
        }
        json.set("valueProviderFactories", valueProviderFactoriesArray);
        json.set("rtreePolicyType", rtreePolicyType.toJson(registry));
        json.putPOJO("rtreeFields", rtreeFields);
        json.putPOJO("buddyBTreeFields", buddyBTreeFields);
        json.put("isPointMBR", isPointMBR);
        json.put("bloomFilterFalsePositiveRate", bloomFilterFalsePositiveRate);
    }

    public static IJsonSerializable fromJson(IPersistedResourceRegistry registry, JsonNode json)
            throws HyracksDataException {
        final int[] buddyBTreeFields = OBJECT_MAPPER.convertValue(json.get("buddyBTreeFields"), int[].class);
        final int[] rtreeFields = OBJECT_MAPPER.convertValue(json.get("rtreeFields"), int[].class);
        final double bloomFilterFalsePositiveRate = json.get("bloomFilterFalsePositiveRate").asDouble();
        final boolean isPointMBR = json.get("isPointMBR").asBoolean();
        final RTreePolicyType rtreePolicyType = (RTreePolicyType) registry.deserialize(json.get("rtreePolicyType"));
        final ILinearizeComparatorFactory linearizeCmpFactory =
                (ILinearizeComparatorFactory) registry.deserialize(json.get("linearizeCmpFactory"));
        final List<IBinaryComparatorFactory> btreeCmpFactoriesList = new ArrayList<>();
        final ArrayNode jsonBtreeCmpFactories = (ArrayNode) json.get("btreeCmpFactories");
        for (JsonNode cf : jsonBtreeCmpFactories) {
            btreeCmpFactoriesList.add((IBinaryComparatorFactory) registry.deserialize(cf));
        }
        final IBinaryComparatorFactory[] btreeCmpFactories =
                btreeCmpFactoriesList.toArray(new IBinaryComparatorFactory[0]);
        final List<IPrimitiveValueProviderFactory> valueProviderFactoriesList = new ArrayList<>();
        final ArrayNode jsonValueProviderFactories = (ArrayNode) json.get("valueProviderFactories");
        for (JsonNode cf : jsonValueProviderFactories) {
            valueProviderFactoriesList.add((IPrimitiveValueProviderFactory) registry.deserialize(cf));
        }
        final IPrimitiveValueProviderFactory[] valueProviderFactories =
                valueProviderFactoriesList.toArray(new IPrimitiveValueProviderFactory[0]);
        return new LSMRTreeLocalResource(registry, json, btreeCmpFactories, valueProviderFactories, rtreePolicyType,
                linearizeCmpFactory, rtreeFields, buddyBTreeFields, isPointMBR, bloomFilterFalsePositiveRate);
    }
}
