/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.tools.nodetool;

import io.airlift.command.Arguments;
import io.airlift.command.Command;
import io.airlift.command.Option;

import java.util.ArrayList;
import java.util.List;

import org.apache.cassandra.tools.NodeProbe;
import org.apache.cassandra.tools.NodeTool.NodeToolCmd;

@Command(name = "verify", description = "Verify (check data checksum for) one or more tables")
public class Verify extends NodeToolCmd
{
    @Arguments(usage = "[<keyspace> <tables>...]", description = "The keyspace followed by one or many tables")
    private List<String> args = new ArrayList<>();

    @Option(title = "extended_verify",
        name = {"-e", "--extended-verify"},
        description = "Verify each cell data, beyond simply checking sstable checksums")
    private boolean extendedVerify = false;

    @Override
    public void execute(NodeProbe probe)
    {
        List<String> keyspaces = parseOptionalKeyspace(args, probe);
        String[] cfnames = parseOptionalColumnFamilies(args);

        for (String keyspace : keyspaces)
        {
            try
            {
                probe.verify(probe.output().out, extendedVerify, keyspace, cfnames);
            } catch (Exception e)
            {
                throw new RuntimeException("Error occurred during verifying", e);
            }
        }
    }
}
