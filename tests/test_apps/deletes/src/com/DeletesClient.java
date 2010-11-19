/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.utils.SnapshotVerifier;

import com.deletes.Insert;

public class DeletesClient
{
    final static int NUM_NAMES = 50;
    static int m_averageBatchSize = 25000;
    static int m_batchesToKeep = 12;
    static int m_deceasedCleanupFreq = -1;
    static int m_snapshotFreq = -1;
    static String m_snapshotId = "Deletes";
    static String m_snapshotDir = "/tmp/deletes";
    static String[] m_names = new String[NUM_NAMES];
    static Random m_rand = new Random(0);
    static long m_batchNumber = 1000;
    static int m_totalRows;
    static long m_highMem = 0;
    static long m_totalInserts = 0;
    static long m_totalInsertedRows = 0;
    static long m_totalInsertTime = 0;
    static long m_expectedInserts = 0;
    static long m_totalDeletes = 0;
    static long m_totalDeletedRows = 0;
    static long m_totalDeleteTime = 0;
    static long m_expectedDeletes = 0;
    static long m_totalDeadDeletes = 0;
    static long m_totalDeadDeleteTime = 0;
    static long m_expectedDeadDeletes = 0;
    static ArrayList<Integer> m_snapshotSizes = new ArrayList<Integer>();
    static boolean m_snapshotInProgress = false;

    static String randomString(int stringSize)
    {
        final String lazyletters = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < stringSize; j++)
        {
            int index = m_rand.nextInt(lazyletters.length());
            sb.append(lazyletters.charAt(index));
        }
        return sb.toString();
    }

    static void generateNames(int nameSize)
    {
        for (int i = 0; i < NUM_NAMES; i++)
        {
            m_names[i] = randomString(nameSize);
        }
    }

    static void insertNewRecord(Client client, long batchNumber)
    {
        int name_idx = m_rand.nextInt(NUM_NAMES);
        long age = m_rand.nextInt(100);
        long weight = m_rand.nextInt(200);
        String desc1 = randomString(250);
        String desc2 = randomString(250);
        String addr1 = randomString(30);
        String addr2 = randomString(120);
        String addr3 = randomString(60);
        String text1 = randomString(120);
        String text2 = randomString(32);
        String sig = randomString(16);
        long ts = batchNumber;
        String company = randomString(60);
        String co_addr = randomString(250);
        byte deceased = (byte) (m_rand.nextBoolean() ? 1 : 0);
        try {
            client.callProcedure(
                    new ProcedureCallback() {

                        @Override
                        public void clientCallback(ClientResponse response) {
                            if (response.getStatus() != ClientResponse.SUCCESS){
                                System.out.println("failed insert");
                                System.out.println(response.getStatusString());
                            }
                            m_expectedInserts--;
                        }

                    },
                    Insert.class.getSimpleName(),
                    m_names[name_idx],
                    age,
                    weight,
                    desc1,
                    desc2,
                    addr1,
                    addr2,
                    addr3,
                    text1,
                    text2,
                    sig,
                    ts,
                    company,
                    co_addr,
                    deceased);
        } catch (NoConnectionsException e) {
            System.err.println("Lost connection to database, terminating");
            System.exit(-1);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void insertBatch(Client client)
    {
        System.out.println("Total rows currently: " + m_totalRows);
        int to_insert = m_rand.nextInt(m_averageBatchSize * 2) + 1;
        System.out.println("Inserting: " + to_insert + " rows");
        m_expectedInserts = to_insert;
        long start_time = System.currentTimeMillis();
        for (int j = 0; j < to_insert; j++)
        {
            insertNewRecord(client, m_batchNumber);
        }
        m_batchNumber++;
        m_totalRows += to_insert;
        while (m_expectedInserts != 0)
        {
            Thread.yield();
        }
        long elapsed = System.currentTimeMillis() - start_time;
        m_totalInserts += to_insert;
        m_totalInsertTime += elapsed;
        System.out.println("Batch: " + to_insert + " took " +
                           elapsed + " millis");
        System.out.println("\t (" + ((to_insert * 1000)/elapsed) + " tps)");
        System.out.println("Total insert TPS: " + (m_totalInserts * 1000)/m_totalInsertTime);
    }


    static void parseStats(ClientResponse resp)
    {
        //System.out.println("table stats: " + resp.getResults()[3]);
        //System.out.println("index stats: " + resp.getResults()[4]);
        // Go ghetto for now and assume we're running on one host.
        VoltTable table_stats = resp.getResults()[4];
        long total_mem = 0;
        for (int ii = 0; ii < table_stats.getRowCount(); ii++) {
            VoltTableRow row = table_stats.fetchRow(ii);
            total_mem += row.getLong("TUPLE_ALLOCATED_MEMORY");
            total_mem += row.getLong("STRING_DATA_MEMORY");
        }
        System.out.println("TOTAL ALLOCATED TABLE MEMORY: " + total_mem * 1000);
        VoltTable index_stats = resp.getResults()[5];
        for (int ii = 0; ii < index_stats.getRowCount(); ii++) {
            VoltTableRow row = index_stats.fetchRow(ii);
            total_mem += row.getLong("MEMORY_ESTIMATE");
        }
        if (total_mem > m_highMem)
        {
            m_highMem = total_mem;
        }
        System.out.println("TOTAL ALLOCATED MEMORY: " + total_mem * 1000);
        System.out.println("LARGEST MEMORY EATEN: " + m_highMem * 1000);
    }

    static void collectStats(Client client)
    {
        try {
            ClientResponse resp = client.callProcedure("@Statistics", "management", 0);
            parseStats(resp);
        } catch (NoConnectionsException e) {
            System.err.println("Lost connection to database, terminating");
            System.exit(-1);
        } catch (IOException e) {
            e.printStackTrace();
        }
        catch (ProcCallException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    static void deleteBatch(Client client, long batchesToKeep)
    {
        long prune_ts = m_batchNumber - batchesToKeep;
        System.out.println("Pruning batches older than batch: " + prune_ts);
        m_expectedDeletes = NUM_NAMES;
        long start_time = System.currentTimeMillis();
        for (int i = 0; i < NUM_NAMES; i++)
        {
            try
            {
                client.callProcedure(
                                     new ProcedureCallback()
                                     {
                                         @Override
                                         public void clientCallback(ClientResponse response) {
                                             if (response.getStatus() != ClientResponse.SUCCESS){
                                                 System.out.println("failed delete batch");
                                                 System.out.println(response.getStatusString());
                                             }
                                             else
                                             {
                                                 m_totalRows -= response.getResults()[0].asScalarLong();
                                                 m_expectedDeletes--;
                                                 m_totalDeletedRows += response.getResults()[0].asScalarLong();
                                             }
                                         }
                                     },
                                     "DeleteOldBatches", m_names[i], prune_ts);
            }
            catch (NoConnectionsException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            catch (IOException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        while (m_expectedDeletes != 0)
        {
            Thread.yield();
        }
        long elapsed = System.currentTimeMillis() - start_time;
        m_totalDeletes += NUM_NAMES;
        m_totalDeleteTime += elapsed;
        System.out.println("After delete, total rows: " + m_totalRows);
        System.out.println("Deleting batch: " + NUM_NAMES + " took " +
                           elapsed + " millis");
        System.out.println("\t (" + ((NUM_NAMES * 1000)/elapsed) + " tps)");
        System.out.println("Total delete TPS: " + (m_totalDeletes * 1000)/m_totalDeleteTime);
        System.out.println("Total delete RPS: " + (m_totalDeletedRows * 1000)/m_totalDeleteTime);
    }

    static void deleteDeceased(Client client)
    {
        System.out.println("Deleting deceased records...");
        m_expectedDeadDeletes = NUM_NAMES;
        long start_time = System.currentTimeMillis();
        for (int i = 0; i < NUM_NAMES; i++)
        {
            try
            {
                client.callProcedure(
                                     new ProcedureCallback()
                                     {
                                         @Override
                                         public void clientCallback(ClientResponse response) {
                                             if (response.getStatus() != ClientResponse.SUCCESS){
                                                 System.out.println("failed delete deceased");
                                                 System.out.println(response.getStatusString());
                                             }
                                             else
                                             {
                                                 m_totalRows -= response.getResults()[0].asScalarLong();
                                                 m_expectedDeadDeletes--;
                                             }
                                         }
                                     },
                                     "DeleteDeceased", m_names[i]);
            }
            catch (NoConnectionsException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            catch (IOException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        while (m_expectedDeadDeletes != 0)
        {
            Thread.yield();
        }
        long elapsed = System.currentTimeMillis() - start_time;
        m_totalDeadDeletes += NUM_NAMES;
        m_totalDeadDeleteTime += elapsed;
        System.out.println("After dead deletes, total rows: " + m_totalRows);
        System.out.println("Deleting deceased: " + NUM_NAMES + " took " +
                           elapsed + " millis");
        System.out.println("\t (" + ((NUM_NAMES * 1000)/elapsed) + " tps)");
        System.out.println("Total delete TPS: " + (m_totalDeadDeletes * 1000)/m_totalDeadDeleteTime);
    }

    // stolen from TestSaveRestoreSysproc
    static void validateSnapshot()
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        PrintStream original = System.out;
        try {
            System.setOut(ps);
            String args[] = new String[] {
                    m_snapshotId,
                    "--dir",
                    m_snapshotDir
            };
            SnapshotVerifier.main(args);
            ps.flush();
            String reportString = baos.toString("UTF-8");
            //System.err.println(reportString);
            if (reportString.startsWith("Snapshot corrupted"))
            {
                System.exit(-1);
            }
        } catch (UnsupportedEncodingException e) {}
          finally {
            System.setOut(original);
        }
    }

    public static void checkSnapshotComplete(Client client)
    {
        // Check for outstanding snapshot
        VoltTable[] results = null;
        try
        {
            results = client.callProcedure("@SnapshotStatus").getResults();
            //System.out.println(results[0]);
        }
        catch (NoConnectionsException e1)
        {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        catch (IOException e1)
        {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        catch (ProcCallException e1)
        {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        m_snapshotInProgress = false;
        while (results[0].advanceRow())
        {
            Long end_time = results[0].getLong("END_TIME");
            if (end_time == 0)
            {
                m_snapshotInProgress = true;
                return;
            }
        }
        if (results[0].getRowCount() > 0)
        {
            validateSnapshot();
        }
    }

    public static void performSnapshot(Client client)
    {
        checkSnapshotComplete(client);
        if (m_snapshotInProgress)
        {
            System.out.println("Snapshot still in progress, bailing");
            return;
        }
        try
        {
            client.callProcedure("@SnapshotDelete", new String[] {m_snapshotDir},
                                 new String[] {m_snapshotId});
        }
        catch (NoConnectionsException e1)
        {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        catch (IOException e1)
        {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        catch (ProcCallException e1)
        {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        // m_totalRows should be accurate at this point
        m_snapshotSizes.add(m_totalRows);
        System.out.println("Performing Snapshot with total rows: " + m_totalRows);
        try
        {
            client.callProcedure(
                                 new ProcedureCallback()
                                 {
                                     @Override
                                     public void clientCallback(ClientResponse response) {
                                         if (response.getStatus() != ClientResponse.SUCCESS)
                                         {
                                             System.out.println("failed snapshot");
                                             System.out.println(response.getStatusString());
                                         }
                                     }
                                 },
                                 "@SnapshotSave", m_snapshotDir, m_snapshotId, 0);
        }
        catch (NoConnectionsException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public static void main(String[] args)
    {
        if (args.length != 4)
        {
            System.err.println("Client args: [average batch size] [num batches to keep] [cleanup frequency] [server list]");
        }
        m_averageBatchSize = Integer.valueOf(args[0]);
        m_batchesToKeep = Integer.valueOf(args[1]);
        m_deceasedCleanupFreq = Integer.valueOf(args[2]);
        m_snapshotFreq = Integer.valueOf(args[3]);

        System.out.println("Starting Deletes app with:");
        System.out.printf("\tAverage batch size of %d\n", m_averageBatchSize);
        System.out.printf("\tKeeping %d batches\n", m_batchesToKeep);
        System.out.printf("\tCleaning up deceased every %d batches\n", m_deceasedCleanupFreq);
        System.out.printf("\tSnapshotting every %d batches\n", m_snapshotFreq);

        String commaSeparatedServers = args[4];

        // parse the server list
        List<String> servers = new LinkedList<String>();
        String[] commaSeparatedServersParts = commaSeparatedServers.split(",");
        for (String server : commaSeparatedServersParts) {
            servers.add(server.trim());
        }

        File tmpdir = new File(m_snapshotDir);
        tmpdir.mkdir();

        generateNames(16);
        Client client = null;
        ClientConfig config = new ClientConfig("program", "none");
        client = ClientFactory.createClient(config);
        for (String server : servers) {
            try {
                client.createConnection(server);
            } catch (UnknownHostException e) {
                e.printStackTrace();
                System.exit(-1);
            } catch (IOException e) {
                System.err.println("Could not connect to database, terminating: (" + server + ")");
                System.exit(-1);
            }
        }

        // Start with sufficient random sized batches
        for (int i = 0; i < m_batchesToKeep; i++)
        {
            insertBatch(client);
        }

        // now add a batch and remove a batch
        long deceased_counter = 0;
        long snapshot_counter = 0;
        while (true)
        {
            insertBatch(client);
            collectStats(client);

            snapshot_counter++;
            if (snapshot_counter == m_snapshotFreq)
            {
                performSnapshot(client);
                snapshot_counter = 0;
            }

            deceased_counter++;
            if (deceased_counter == m_deceasedCleanupFreq)
            {
                deleteDeceased(client);
                deceased_counter = 0;
            }

            deleteBatch(client, m_batchesToKeep);
        }
    }
}