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
package org.apache.asterix.app.nc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class IndexCheckpointManagerTest {

    private File root;
    private IndexCheckpointManager checkpointManager;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("index_checkpoint_manager_test").toFile();
        Path indexPath = Files.createDirectories(root.toPath().resolve("index"));
        checkpointManager = new IndexCheckpointManager(indexPath);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteDirectory(root);
    }

    /**
     * A write whose read-back succeeded is what the next read returns, without the disk being consulted again.
     */
    @Test
    public void testLatestFollowsWrites() throws Exception {
        checkpointManager.init(0, 10, 1, null);
        Assert.assertEquals(1, checkpointManager.getLatest().getLastComponentId());
        checkpointManager.flushed(1, 20, 2);
        Assert.assertEquals(2, checkpointManager.getLatest().getLastComponentId());
        checkpointManager.setLastComponentId(5);
        Assert.assertEquals(5, checkpointManager.getLatest().getLastComponentId());
        Assert.assertEquals(20, checkpointManager.getLowWatermark());

        checkpointManager.delete();
        Assert.assertFalse(checkpointManager.isValidIndex());
        checkpointManager.init(0, 40, 7, null);
        Assert.assertEquals(7, checkpointManager.getLatest().getLastComponentId());
        Assert.assertEquals(40, checkpointManager.getLowWatermark());
    }
}
