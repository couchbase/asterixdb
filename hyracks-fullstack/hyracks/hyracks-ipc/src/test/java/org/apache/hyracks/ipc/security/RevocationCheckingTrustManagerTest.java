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
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.hyracks.api.network.ICertificateRevocationChecker;
import org.apache.hyracks.util.annotations.AiProvenance;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@AiProvenance(agent = AiProvenance.Agent.CLAUDE_OPUS_4_8, tool = AiProvenance.Tool.CLAUDE_CODE_UI, contributionKind = AiProvenance.ContributionKind.TEST_GENERATED)
public class RevocationCheckingTrustManagerTest {

    private static final X509Certificate[] CHAIN = new X509Certificate[0];
    private static final String AUTH_TYPE = "RSA";

    private RecordingTrustManager delegate;
    private RecordingChecker checker;
    private RevocationCheckingTrustManager tm;

    @Before
    public void setup() {
        delegate = new RecordingTrustManager();
        checker = new RecordingChecker();
        tm = new RevocationCheckingTrustManager(delegate, checker);
    }

    // -- success path: delegate (PKIX) runs first, then the revocation checker, for every overload --

    @Test
    public void checkClientTrusted_callsDelegateThenChecker() throws Exception {
        tm.checkClientTrusted(CHAIN, AUTH_TYPE);
        assertDelegateThenChecker("checkClientTrusted");
    }

    @Test
    public void checkClientTrusted_socket_callsDelegateThenChecker() throws Exception {
        tm.checkClientTrusted(CHAIN, AUTH_TYPE, (Socket) null);
        assertDelegateThenChecker("checkClientTrusted");
    }

    @Test
    public void checkClientTrusted_engine_callsDelegateThenChecker() throws Exception {
        tm.checkClientTrusted(CHAIN, AUTH_TYPE, (SSLEngine) null);
        assertDelegateThenChecker("checkClientTrusted");
    }

    @Test
    public void checkServerTrusted_callsDelegateThenChecker() throws Exception {
        tm.checkServerTrusted(CHAIN, AUTH_TYPE);
        assertDelegateThenChecker("checkServerTrusted");
    }

    @Test
    public void checkServerTrusted_socket_callsDelegateThenChecker() throws Exception {
        tm.checkServerTrusted(CHAIN, AUTH_TYPE, (Socket) null);
        assertDelegateThenChecker("checkServerTrusted");
    }

    @Test
    public void checkServerTrusted_engine_callsDelegateThenChecker() throws Exception {
        tm.checkServerTrusted(CHAIN, AUTH_TYPE, (SSLEngine) null);
        assertDelegateThenChecker("checkServerTrusted");
    }

    // -- a revoked cert (checker throws) aborts the handshake --

    @Test
    public void checkerException_propagates() {
        checker.toThrow = new CertificateException("revoked");
        Assert.assertThrows(CertificateException.class, () -> tm.checkClientTrusted(CHAIN, AUTH_TYPE));
        Assert.assertThrows(CertificateException.class, () -> tm.checkServerTrusted(CHAIN, AUTH_TYPE));
    }

    // -- if PKIX validation fails, the checker is never consulted --

    @Test
    public void delegateException_shortCircuitsChecker() {
        delegate.toThrow = new CertificateException("not trusted");
        Assert.assertThrows(CertificateException.class, () -> tm.checkClientTrusted(CHAIN, AUTH_TYPE));
        Assert.assertEquals(0, checker.calls);
    }

    @Test
    public void getAcceptedIssuers_delegates() {
        Assert.assertSame(RecordingTrustManager.ISSUERS, tm.getAcceptedIssuers());
    }

    private void assertDelegateThenChecker(String expectedDelegateMethod) {
        Assert.assertEquals("delegate should be called exactly once", 1, delegate.calls.size());
        Assert.assertEquals(expectedDelegateMethod, delegate.calls.get(0));
        Assert.assertEquals("checker should be called exactly once", 1, checker.calls);
        Assert.assertSame("checker must receive the same chain", CHAIN, checker.lastChain);
        Assert.assertEquals(AUTH_TYPE, checker.lastAuthType);
    }

    private static final class RecordingChecker implements ICertificateRevocationChecker {
        int calls;
        X509Certificate[] lastChain;
        String lastAuthType;
        CertificateException toThrow;

        @Override
        public void checkRevocation(X509Certificate[] chain, String authType) throws CertificateException {
            calls++;
            lastChain = chain;
            lastAuthType = authType;
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }

    private static final class RecordingTrustManager extends X509ExtendedTrustManager {
        static final X509Certificate[] ISSUERS = new X509Certificate[0];
        final List<String> calls = new ArrayList<>();
        CertificateException toThrow;

        private void record(String method) throws CertificateException {
            calls.add(method);
            if (toThrow != null) {
                throw toThrow;
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            record("checkClientTrusted");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            record("checkClientTrusted");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            record("checkClientTrusted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            record("checkServerTrusted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            record("checkServerTrusted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            record("checkServerTrusted");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return ISSUERS;
        }
    }
}
