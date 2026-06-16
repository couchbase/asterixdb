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
package org.apache.hyracks.api.network;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.apache.hyracks.util.annotations.AiProvenance;

/**
 * Hook invoked during the TLS handshake (after standard PKIX path validation) to check a peer certificate chain for
 * revocation. Implementations are provided by the embedding application via
 * {@link INetworkSecurityConfig#getCertificateRevocationChecker()} and are kept free of any application-specific types
 * so {@code hyracks-ipc} stays decoupled from the auth layer.
 */
@AiProvenance(agent = AiProvenance.Agent.CLAUDE_OPUS_4_8, tool = AiProvenance.Tool.CLAUDE_CODE_UI, contributionKind = AiProvenance.ContributionKind.GENERATED)
@FunctionalInterface
public interface ICertificateRevocationChecker {

    /**
     * @param chain the peer certificate chain that has already passed PKIX validation (end-entity first)
     * @param authType the key-exchange / authentication type, as passed to the delegate {@code X509TrustManager}
     * @throws CertificateException if the connection should be rejected on revocation grounds
     */
    void checkRevocation(X509Certificate[] chain, String authType) throws CertificateException;
}
