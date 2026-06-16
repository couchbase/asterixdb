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
package org.apache.hyracks.ipc.security;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.hyracks.api.network.ICertificateRevocationChecker;
import org.apache.hyracks.util.annotations.AiProvenance;

/**
 * Wraps a delegate {@link X509ExtendedTrustManager} so that, after the delegate performs standard PKIX path validation,
 * an {@link ICertificateRevocationChecker} is consulted to reject revoked peer certificates. Both
 * {@code checkClientTrusted} and {@code checkServerTrusted} are intercepted, so revocation is enforced for both
 * inbound and outbound connections (mirroring a {@code VerifyPeerCertificate} callback).
 */
@AiProvenance(agent = AiProvenance.Agent.CLAUDE_OPUS_4_8, tool = AiProvenance.Tool.CLAUDE_CODE_UI, contributionKind = AiProvenance.ContributionKind.GENERATED)
public class RevocationCheckingTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager delegate;
    private final ICertificateRevocationChecker checker;

    public RevocationCheckingTrustManager(X509ExtendedTrustManager delegate, ICertificateRevocationChecker checker) {
        this.delegate = delegate;
        this.checker = checker;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
        checker.checkRevocation(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
        checker.checkRevocation(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
        checker.checkRevocation(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        checker.checkRevocation(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
        checker.checkRevocation(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
        checker.checkRevocation(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
