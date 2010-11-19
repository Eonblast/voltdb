/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.utils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.HashMap;

import javax.servlet_voltpatches.ServletException;
import javax.servlet_voltpatches.http.HttpServletRequest;
import javax.servlet_voltpatches.http.HttpServletResponse;

import org.eclipse.jetty_voltpatches.server.Request;
import org.eclipse.jetty_voltpatches.server.Server;
import org.eclipse.jetty_voltpatches.server.bio.SocketConnector;
import org.eclipse.jetty_voltpatches.server.handler.AbstractHandler;
import org.voltdb.processtools.ShellTools;

/**
 * SystemStatsCollector stores a history of system memory usage samples.
 * Generating a sample is a manually instigated process that must be done
 * periodically.
 * It stored history in three buckets, each with a fixed size.
 * Each bucket should me more granular than the last.
 *
 */
public class SystemStatsCollector {

    static long cputime;
    static long elapsedtime;
    static final long javamaxheapmem = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    static long memorysize = 256;
    static int pid;
    static boolean initialized = false;
    static Thread thread = null;

    final static ArrayDeque<Datum> historyL = new ArrayDeque<Datum>(); // every hour
    final static ArrayDeque<Datum> historyM = new ArrayDeque<Datum>(); // every minute
    final static ArrayDeque<Datum> historyS = new ArrayDeque<Datum>(); // every 5 seconds
    final static int historySize = 720;

    /**
     * Datum class is one sample of memory usage.
     */
    public static class Datum {
        public final long timestamp;
        public final long rss;
        public final double pmem;
        public final double pcpu;
        public final long cputime;
        public final long javatotalheapmem;
        public final long javausedheapmem;
        public final long javatotalsysmem;
        public final long javausedsysmem;

        /**
         * Constructor accepts some system values and generates some Java values.
         *
         * @param rss Resident set size.
         * @param pmem Percent of memory used.
         * @param pcpu Percent of cpu used.
         * @param cputime Total cpu usage time.
         * @param elapsedtime Total running time of process.
         */
        Datum(long rss, double pmem, double pcpu, long cputime, long elapsedtime) {
            MemoryMXBean mmxb = ManagementFactory.getMemoryMXBean();
            MemoryUsage muheap = mmxb.getHeapMemoryUsage();
            MemoryUsage musys = mmxb.getNonHeapMemoryUsage();

            timestamp = System.currentTimeMillis();
            this.rss = rss;
            this.pmem = pmem / 100;
            this.pcpu = pcpu;
            this.cputime = cputime;
            javatotalheapmem = muheap.getCommitted();
            javausedheapmem = muheap.getUsed();
            javatotalsysmem = musys.getCommitted();
            javausedsysmem = musys.getUsed();

            long memorysizeTemp = Math.round(rss / this.pmem / 1024 / 1024 / 1024);
            memorysizeTemp *= 1024 * 1024 * 1024;
            /*if (memorysizeTemp > 256)*/ memorysize = memorysizeTemp;

        }

        /**
         * @return Print-friendly string for this Datum.
         */
        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append(String.format("%dms:\n", timestamp));
            sb.append(String.format("  SYS: %dM RSS, %.2f%% PMEM, %.2f%% CPU, %dM Total\n",
                    rss / 1024 /1024,
                    pmem,
                    pcpu,
                    memorysize / 1024 / 1024));
            sb.append(String.format("  JAVA: HEAP(%d/%d/%dM) SYS(%d/%dM)\n",
                    javausedheapmem / 1024 / 1024,
                    javatotalheapmem / 1024 / 1024,
                    javamaxheapmem / 1024 / 1024,
                    javausedsysmem / 1024 / 1024,
                    javatotalsysmem / 1024 / 1024));
            return sb.toString();
        }

        /**
         * @return A CSV-formatted line for this Datum
         */
        String toLine() {
            return String.format("%d,%d,%d,%d,%d,%d",
                    timestamp,
                    rss,
                    javausedheapmem,
                    javatotalheapmem,
                    javausedsysmem,
                    javatotalsysmem);
        }
    }

    public static long getDurationFromPSString(String duration) {
        String[] parts;

        // split into days and sub-days
        duration = duration.trim();
        parts = duration.split("-");
        assert(parts.length > 0);
        assert(parts.length <= 2);
        String dayString = "0"; if (parts.length == 2) dayString = parts[0];
        String subDayString = parts[parts.length - 1];
        long days = Long.parseLong(dayString);

        // split into > seconds in 00:00:00 time and second fractions
        subDayString = subDayString.trim();
        parts = subDayString.split("\\.");
        assert(parts.length > 0);
        assert(parts.length <= 2);
        String fractionString = "0"; if (parts.length == 2) fractionString = parts[parts.length - 1];
        subDayString = parts[0];
        while (fractionString.length() < 3) fractionString += "0";
        long miliseconds = Long.parseLong(fractionString);

        // split into hours,minutes,seconds
        parts = subDayString.split(":");
        assert(parts.length > 0);
        assert(parts.length <= 3);
        String hoursString = "0"; if (parts.length == 3) hoursString = parts[parts.length - 3];
        String minutesString = "0"; if (parts.length >= 2) minutesString = parts[parts.length - 2];
        String secondsString = parts[parts.length - 1];
        long hours = Long.parseLong(hoursString);
        long minutes = Long.parseLong(minutesString);
        long seconds = Long.parseLong(secondsString);

        // compound down to ms
        hours = hours + (days * 24);
        minutes = minutes + (hours * 60);
        seconds = seconds + (minutes * 60);
        miliseconds = miliseconds + (seconds * 1000);
        return miliseconds;
    }

    /**
     * Synchronously collect memory stats.
     * @param medium Add result to medium set?
     * @param large Add result to large set?
     * @return The generated Datum instance.
     */
    public static Datum sampleSystemNow(final boolean medium, final boolean large) {
        Datum d = generateCurrentSample();
        if (d == null)
            return null;
        historyS.addLast(d);
        if (historyS.size() > historySize) historyS.removeFirst();
        if (medium) {
            historyM.addLast(d);
            if (historyM.size() > historySize) historyM.removeFirst();
        }
        if (large) {
            historyL.addLast(d);
            if (historyL.size() > historySize) historyL.removeFirst();

        }
        return d;
    }

    /**
     * Fire off a thread to asynchronously collect stats.
     * @param medium Add result to medium set?
     * @param large Add result to large set?
     */
    public static synchronized void asyncSampleSystemNow(final boolean medium, final boolean large) {
        if (thread != null) {
            if (thread.isAlive()) return;
            else thread = null;
        }

        thread = new Thread(new Runnable() {
            @Override
            public void run() { sampleSystemNow(medium, large); }
        });
        thread.start();
    }

    /**
     * @return The most recently generated Datum.
     */
    public static synchronized Datum getRecentSample() {
        return historyS.getLast();
    }

    /**
     * Poll the operating system and generate a Datum
     * @return A newly created Datum instance.
     */
    private static synchronized Datum generateCurrentSample() {
        // get this info once
        if (!initialized) {
            String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            String pidString = processName.substring(0, processName.indexOf('@'));
            pid = Integer.valueOf(pidString);
        }

        // run "ps" to get stats for this pid
        String command = String.format("ps -p %d -o rss,pmem,pcpu,time,etime", pid);
        String results = ShellTools.cmd(command);

        // parse ps into value array
        String[] lines = results.split("\n");
        if (lines.length != 2)
            return null;
        results = lines[1];
        results = results.trim();
        String[] values = results.split("\\s+");

        // tease out all the stats
        long rss = Long.valueOf(values[0]) * 1024;
        double pmem = Double.valueOf(values[1]);
        double pcpu = Double.valueOf(values[2]);
        long time = getDurationFromPSString(values[3]);
        long etime = getDurationFromPSString(values[4]);

        // create a new Datum which adds java stats
        Datum d = new Datum(rss, pmem, pcpu, time, etime);
        //System.out.println(d);
        return d;
    }

    /**
     * Get a CSV string of all the values in the history,
     * filtering for uniqueness.
     * @return A string containing CSV memory values.
     */
    public static synchronized String getCSV() {
        // build a unique set
        HashMap<Long, Datum> all =  new HashMap<Long, Datum>();
        for (Datum d : historyS)
            all.put(d.timestamp, d);
        for (Datum d : historyM)
            all.put(d.timestamp, d);
        for (Datum d : historyL)
            all.put(d.timestamp, d);

        // print the csv out
        StringBuilder sb = new StringBuilder();
        for (Datum d : all.values())
            sb.append(d.toLine()).append("\n");

        return sb.toString();
    }

    /**
     * Get a URL that uses the Google Charts API to show a chart of memory usage history.
     *
     * @param minutes The number of minutes the chart should cover. Tested values are 2, 30 and 1440.
     * @param width The width of the chart image in pixels.
     * @param height The height of the chart image in pixels.
     * @param timeLabel The text to put under the left end of the x axis.
     * @return A String containing the URL of the chart.
     */
    public static synchronized String getGoogleChartURL(int minutes, int width, int height, String timeLabel) {

        ArrayDeque<Datum> history = historyS;
        if (minutes > 2) history = historyM;
        if (minutes > 30) history = historyL;

        HTMLChartHelper chart = new HTMLChartHelper();
        chart.width = width;
        chart.height = height;
        chart.timeLabel = timeLabel;

        HTMLChartHelper.DataSet Jds = new HTMLChartHelper.DataSet();
        chart.data.add(Jds);
        Jds.title = "UsedJava";
        Jds.belowcolor = "ff9999";

        HTMLChartHelper.DataSet Rds = new HTMLChartHelper.DataSet();
        chart.data.add(Rds);
        Rds.title = "RSS";
        Rds.belowcolor = "ff0000";

        HTMLChartHelper.DataSet RUds = new HTMLChartHelper.DataSet();
        chart.data.add(RUds);
        RUds.title = "RSS+UnusedJava";
        RUds.dashlength = 6;
        RUds.spacelength = 3;
        RUds.thickness = 2;
        RUds.belowcolor = "ffffff";

        long cropts = System.currentTimeMillis();
        cropts -= (60 * 1000 * minutes);
        long modulo = (60 * 1000 * minutes) / 30;

        double maxmemdatum = 0;

        for (Datum d : history) {
            if (d.timestamp < cropts) continue;

            double javaused = d.javausedheapmem + d.javausedsysmem;
            double javaunused = d.javatotalheapmem + d.javatotalsysmem - javaused;
            javaused /= 1204 * 1024;
            javaunused /= 1204 * 1024;
            double rss = d.rss / 1024 / 1024;

            long ts = (d.timestamp / modulo) * modulo;

            if ((rss + javaunused) > maxmemdatum)
                maxmemdatum = rss + javaunused;

            RUds.append(ts, rss + javaunused);
            Rds.append(ts, rss);
            Jds.append(ts, javaused);
        }

        chart.megsMax = 2;
        while (chart.megsMax < maxmemdatum)
            chart.megsMax *= 2;

        return chart.getURL(minutes);
    }

    /**
     * Web server handler for manual testing. Returns a chart image page.
     */
    static class RequestHandler extends AbstractHandler {

        @Override
        public void handle(String target,
                           Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response)
                           throws IOException, ServletException {

            String strURL = getGoogleChartURL(2, 320, 240, "-2min");

            // just print voltdb version for now
            String msg = "<html><body>\n";
            msg += "<h2>VoltDB RAM Usage</h2>\n";
            msg += "<img src='" + strURL + "' />\n";
            msg += "</body></html>";

            System.out.println(msg);
            System.out.println(msg.length());

            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
            response.getWriter().print(msg);
        }

    }

    /**
     * Main for manual testing that opens a http server on 8080
     * that serves a chart based on history data.
     */
    public static void main(String[] args) {
        Server server = new Server();

        try {
            // The socket channel connector seems to be faster for our use
            //SelectChannelConnector connector = new SelectChannelConnector();
            SocketConnector connector = new SocketConnector();

            connector.setPort(8080);
            connector.setName("VoltDB-HTTPD");
            server.addConnector(connector);

            server.setHandler(new RequestHandler());
            server.start();
        }
        catch (Exception e) {
            // double try to make sure the port doesn't get eaten
            try { server.stop(); } catch (Exception e2) {}
            try { server.destroy(); } catch (Exception e2) {}
            throw new RuntimeException(e);
        }

        while(true) {
            asyncSampleSystemNow(false, false);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            //Datum d = getRecentSample();
            //System.out.println(d);
        }
    }

}