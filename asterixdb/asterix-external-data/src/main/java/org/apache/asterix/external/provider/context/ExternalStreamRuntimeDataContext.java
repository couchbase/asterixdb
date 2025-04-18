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
package org.apache.asterix.external.provider.context;

import org.apache.asterix.external.input.filter.embedder.IExternalFilterValueEmbedder;
import org.apache.hyracks.api.context.IHyracksTaskContext;

public class ExternalStreamRuntimeDataContext extends DefaultExternalRuntimeDataContext {
    private final IExternalFilterValueEmbedder valueEmbedder;

    public ExternalStreamRuntimeDataContext(IHyracksTaskContext context, int partition,
            IExternalFilterValueEmbedder valueEmbedder) {
        super(context, partition);
        this.valueEmbedder = valueEmbedder;
    }

    @Override
    public IExternalFilterValueEmbedder getValueEmbedder() {
        return valueEmbedder;
    }
}
