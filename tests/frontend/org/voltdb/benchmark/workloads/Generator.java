package org.voltdb.benchmark.workloads;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.voltdb.benchmark.*;

import java.io.*;
import java.util.*;
import java.lang.reflect.*;

import java.net.UnknownHostException;
import java.net.ConnectException;

import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.client.Client;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.VoltProcedure.VoltAbortException;

import org.voltdb.benchmark.workloads.xml.*;
import org.voltdb.benchmark.workloads.procedures.*;


//COMMAND: xjc -p benchmarkGenerator.xml /home/voltdb/mstarobinets/Desktop/Useful/MB/microbenchmark1.xsd -d /home/voltdb/mstarobinets/Desktop/Useful/MB
//ADDRESS: /home/voltdb/mstarobinets/Desktop/Useful/MB/microbenchmark1.xml
public class Generator extends ClientMain
{
    public class Workload
    {
        private final String name;
        private String[] procs;
        private double[] percs;
        private String[][] paramTypes;
        private GeneratorType[][] generatorTypes;
        private Object[][] params;

        public Workload(String name)
        {
            this.name = name;
        }
    }

    public static class GenericCallback implements ProcedureCallback
    {
        private static int count = 0;
        public void clientCallback(ClientResponse clientResponse)
        {
            //do error checking
        }
    }

 // Retrieved via reflection by BenchmarkController
    public static final Class<? extends VoltProjectBuilder> m_projectBuilderClass = ProjectBuilderX.class;

    //Retrieved via reflection by BenchmarkController
    //public static final Class<? extends ClientMain> m_loaderClass = anyLoader.class;
    public static final Class<? extends ClientMain> m_loaderClass = null;

    public static final String m_jarFileName = "catalog.jar";

    private Microbenchmark mb;
    private LinkedList<Workload> workloads;
    private Workload currWorkload;

    private GenericCallback callback = new GenericCallback();

    public Generator(String[] args)
    {
        super(args);

        mb = null;
        workloads = new LinkedList<Workload>();
        currWorkload = null;
    }

    public static void main(String[] args)
    {
        ClientMain.main(Generator.class, args, false);
/*
        Generator g = new Generator();
        try
        {
            g.runLoop();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }
*/
    }

    private void runBenchmark()
    {
        connect();

        mb = unmarshal(getXMLFile());

        boolean loaded = runLoader(mb);
        testPrint();

        if (loaded || userTrueFalse("Continue?", "yes", "no"))
            if (buildWorkloads(mb))
            {
                ListIterator<Workload> iter = workloads.listIterator();
                while (iter.hasNext())
                {
                    currWorkload = iter.next();
                    try
                    {
                        System.out.println("About to run workload " + currWorkload.name);
                        runRandomizedWorkload(currWorkload, getTotalTime());
                        testPrint();
                    }
                    catch (Exception e)
                    {
                        System.out.println("Invalid inputs in xml file for workload \"" + currWorkload.name + "\".");
                    }
                }
            }

        disconnect();
    }

    public void connect()
    {
        System.out.println("Client started");

        try
        {
            m_voltClient.createConnection("localhost", "program", "none");
        }
        catch (UnknownHostException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }
        catch (ConnectException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public void disconnect()
    {
        try
        {
            m_voltClient.drain();
            m_voltClient.close();
            System.out.println("Client finished");
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
        catch (NoConnectionsException e)
        {
            System.out.println("No connection exception: " + e.getMessage());
        }
    }

    //first two parameters are specified in config file
    //last parameter entered from command line by user (total time in nanoseconds)
    public void runRandomizedWorkload(Workload currWorkload, long totalTime)
        throws Exception
    {
        //if procArray.length != percArray.length throw error
        if (!(currWorkload.procs.length == currWorkload.percs.length && currWorkload.percs.length == currWorkload.params.length))
            throw new Exception();

        if (currWorkload.procs.length > 0)
            //throw new Exception();
        {
            double[] cumPercArray = new double[currWorkload.percs.length];
            cumPercArray[0] = currWorkload.percs[0];
            for (int i = 1; i < currWorkload.percs.length; i++)
                cumPercArray[i] = cumPercArray[i - 1] + currWorkload.percs[i];

            if (cumPercArray[cumPercArray.length - 1] != 100.)
                throw new Exception();

            double randomVal;
            final long startTime = System.nanoTime();
            long currentTime = System.nanoTime();
            int numProcCalls = 0;
            while (currentTime - startTime < totalTime)
            {
                randomVal = Math.random() * 100;
                int index = 0;
                while (randomVal > cumPercArray[index])
                //&& while array index not out of bounds
                    index++;

                for (int i = 0; i < currWorkload.params[index].length; i++)
                    setParams(currWorkload, index, i);
                callProc(currWorkload.procs[index], currWorkload.params[index]);
                numProcCalls++;
                currentTime = System.nanoTime();
            }
            long timeElapsed = currentTime - startTime;
            System.out.println("Workload " + currWorkload.name +
                    " made " + numProcCalls + " procedure calls in time: " +
                    (timeElapsed / ((long)1000000000 * 60 * 60)) + "h" +
                    (timeElapsed % ((long)1000000000 * 60 * 60) / ((long)1000000000 * 60)) + "m" +
                    (timeElapsed % ((long)1000000000 * 60) / (long)1000000000) + "s" +
                    (timeElapsed % (long)1000000000) + "n");
        }
    }

    private long getTotalTime()
    {
        long nanoTime = 0;

        Scanner scan;
        boolean inputValid = false;
        while (!inputValid)
        {
            System.out.print("Enter duration of workload with units in descending order (ex. 1h2m3s4n, 3m 1n, etc.): ");
            scan = new Scanner(System.in);
            String input = scan.nextLine();
            //do try catch, negative values...
            if (input.matches("(\\d+\\s*h)?\\s*(\\d+\\s*m)?\\s*(\\d+\\s*s)?\\s*(\\d+\\s*n)?\\s*") && !input.trim().equals(""))
            {
                try
                {
                    input = input.replaceAll("\\s", "");

                    if (input.contains("h"))
                    {
                        int index = input.indexOf('h');
                        long value = Integer.parseInt(input.substring(0, index));
                        if ((Long.MAX_VALUE - nanoTime) / 1000000000. / 60 / 60 < value)
                        {
                            System.out.println("Value exceeds limit of about 2.5 million total hours");
                            throw new Exception();
                        }
                        else
                        {
                            nanoTime += value * 1000000000 * 60 * 60;
                            input = input.substring(index + 1);
                        }
                    }
                    if (input.contains("m"))
                    {
                        int index = input.indexOf('m');
                        long value = Integer.parseInt(input.substring(0, index));
                        if ((Long.MAX_VALUE - nanoTime) / 1000000000. / 60 < value)
                        {
                            System.out.println("Value exceeds limit of about 150 million total minutes");
                            throw new Exception();
                        }
                        else
                        {
                            nanoTime += value * 1000000000 * 60;
                            input = input.substring(index + 1);
                        }
                    }
                    if (input.contains("s"))
                    {
                        int index = input.indexOf('s');
                        long value = Integer.parseInt(input.substring(0, index));
                        if ((Long.MAX_VALUE - nanoTime) / 1000000000. < value)
                        {
                            System.out.println("Value exceeds limit of about 9 billion total seconds");
                            throw new Exception();
                        }
                        else
                        {
                            nanoTime += value * 1000000000;
                            input = input.substring(index + 1);
                        }
                    }
                    if (input.contains("n"))
                    {
                        int index = input.indexOf('n');
                        long value = Integer.parseInt(input.substring(0, index));
                        if ((Long.MAX_VALUE - nanoTime) < value)
                        {
                            System.out.println("Value exceeds limit of about 9 quintillion total nanoseconds");
                            throw new Exception();
                        }
                        else
                        {
                            nanoTime += value;
                            input = input.substring(index + 1);
                        }
                    }

                    inputValid = true;
                }
                catch (Exception e)
                {
                    System.out.println("Parse error.");
                }
            }
            else
            {
                System.out.println("Regex error.");
            }
        }

        return nanoTime;
    }

    private void callProc(String procName, Object[] params)
    {
        //check procName validity with catch statement
        try
        {
            m_voltClient.callProcedure(callback, procName, params);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        /*
        catch (ProcCallException e)
        {
            System.out.println("Procedure call error: " + e.getMessage());
        }
        */
    }

    //should these be public or protected?
    @Override
    public void runLoop()
        throws IOException
    {
        runBenchmark();
    }

    @Override
    public String[] getTransactionDisplayNames()
    {
        if (currWorkload == null || currWorkload.procs == null)
        {
            String[] displayNames = new String[1];
            displayNames[0] = "Loading or building workloads.";
            return displayNames;
        }

        String[] displayNames = new String[currWorkload.procs.length];
        for (int i = 0; i < displayNames.length; i++)
            displayNames[i] = currWorkload.procs[i];
        return displayNames;
    }

    @Override
    public String getApplicationName()
    {
        if (mb == null)
            return "Building microbenchmark.";
        else
            return "Microbenchmark: " + mb.getMbName();
    }

    @Override
    public String getSubApplicationName()
    {
        if (currWorkload == null)
            return "Loading or building workloads.";
        else
            return "Workload: " + currWorkload.name;
    }

    //ADD FEEDBACK: PRINTOUTS/FILEWRITES ABOUT CREATED WORKLOADS
    private boolean buildWorkloads(Microbenchmark mb)
    {
        if (mb == null)
            return false;

        try
        {
            List<Microbenchmark.Workload> wlList = mb.getWorkload();

            ListIterator<Microbenchmark.Workload> wlLI = wlList.listIterator();
            while (wlLI.hasNext())
            {
                Microbenchmark.Workload wl = wlLI.next();
                Workload myWL = new Workload(wl.getWlName());

                System.out.println("Building workload " + myWL.name);

                List<Microbenchmark.Workload.Procedure> procList = wl.getProcedure();
                myWL.procs = new String[procList.size()];
                myWL.percs = new double[procList.size()];
                myWL.paramTypes = new String[procList.size()][];
                myWL.generatorTypes = new GeneratorType[procList.size()][];
                myWL.params = new Object[procList.size()][];

                ListIterator<Microbenchmark.Workload.Procedure> procLI = procList.listIterator();
                int procIndex = 0;
                while (procLI.hasNext())
                {
                    Microbenchmark.Workload.Procedure proc = procLI.next();
                    myWL.procs[procIndex] = proc.getProcName();
                    myWL.percs[procIndex] = proc.getPercOfWL().doubleValue();
                    List<Microbenchmark.Workload.Procedure.Param> paramList = proc.getParam();
                    myWL.paramTypes[procIndex] = new String[paramList.size()];
                    myWL.generatorTypes[procIndex] = new GeneratorType[paramList.size()];
                    myWL.params[procIndex] = new Object[paramList.size()];

                    ListIterator<Microbenchmark.Workload.Procedure.Param> paramLI = paramList.listIterator();
                    int paramIndex = 0;
                    while (paramLI.hasNext())
                    {
                        Microbenchmark.Workload.Procedure.Param param = paramLI.next();
                        myWL.paramTypes[procIndex][paramIndex] = param.getType();
                        myWL.generatorTypes[procIndex][paramIndex] = param.getValue().getGenerator();

                        paramIndex++;
                    }
                    procIndex++;
                }
                workloads.add(myWL);
            }

            System.out.println("All workloads successfully built.");
            return true;
        }
        catch (Exception e)
        {
            System.out.println("Building failed due to syntax errors in XML file.");
            return false;
        }
    }

    private boolean runLoader(Microbenchmark mb)
    {
        if (mb == null)
            return false;

        Microbenchmark.Loader loader = mb.getLoader();
        System.out.println("Running loader " + loader.getLoaderName());
        List<Microbenchmark.Loader.LoaderClass> loaderClasses = loader.getLoaderClass();

        ListIterator<Microbenchmark.Loader.LoaderClass> loaderClassLI = loaderClasses.listIterator();
        String pathName;
        while (loaderClassLI.hasNext())
        {
            pathName = loaderClassLI.next().getPathName();

            try
            {
                Class<?> loaderClass = Class.forName(pathName);
                Method[] methods = loaderClass.getMethods();
                //for (int i = 0; i < methods.length; i++)
                //    System.out.println(i + ": " + methods[i].getName());
                //Method run = loaderClass.getMethod("run", m_voltClient.getClass());
                Method run = methods[0];
                run.invoke(null, m_voltClient);
            }
            catch (Exception e)
            {
                System.out.println("Retrieving loader class with path name " + pathName + " failed.");
                e.printStackTrace();
                return false;
            }
        }

        System.out.println("All loading successfully completed.");
        return true;
    }

    //HOW TO HANDLE ERRORS IN XML FILE WHERE TYPE DOESN'T MATCH
    private void setParams(Workload myWL, int procIndex, int paramIndex)
    {
        String type = myWL.paramTypes[procIndex][paramIndex];
        GeneratorType generator = myWL.generatorTypes[procIndex][paramIndex];
        Object randVal = null;

        //ADD CHAR() AND VARCHAR() HANDLING
        if (type.equals("CHAR"))
        {
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getString(1, true); break;
                //case USER: Handle TYPE errors
            }
        }
        else if (type.startsWith("CHAR("))
        {
            int index = type.indexOf(')');
            String temp = type.substring(5, index);
            int length = Integer.valueOf(temp);
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getString(length, true); break;
                //case USER: Handle TYPE errors
            }
        }
        else if (type.startsWith("VARCHAR("))
        {
            int index = type.indexOf(')');
            String temp = type.substring(8, index);
            int length = Integer.valueOf(temp);
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getString(length, false); break;
                //case USER: Handle TYPE errors
            }
        }
        else if (type.equals("FLOAT"))
        {
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getDouble(); break;
                //case USER: //
            }
        }
        else if (type.equals("DECIMAL"))
        {
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getBigDecimal(); break;
                //case USER: //
            }
        }
        else if (type.equals("BIGINT"))
        {
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getLong(); break;
                //case USER: //
            }
        }
        else if (type.equals("INTEGER"))
        {
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getInt(); break;
                //case USER: //
            }
        }
        else if (type.equals("SHORTINT"))
        {
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getShort(); break;
                //case USER: //
            }
        }
        else if (type.equals("TINYINT"))
        {
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getByte(); break;
                //case USER: //
            }
        }
        else if (type.equals("TIMESTAMP"))
        {
            switch (generator)
            {
                case RANDOM: randVal = RandomValues.getTimestamp(); break;
                //case USER: //
            }
        }
        else //ADD EXCEPTION HANDLING
            ;

        myWL.params[procIndex][paramIndex] = randVal;
    }

    private File getXMLFile()
    {
        String pathName = "";
        File file = null;

        Scanner scan;
        boolean fileFound = false;
        while (!fileFound)
        {
            scan = new Scanner(System.in);
            System.out.print("Enter absolute path for XML file: ");
            pathName = scan.nextLine();
            file = new File(pathName);
            fileFound = file.exists() && pathName.toLowerCase().endsWith(".xml");
        }

        System.out.println("XML file found.");
        return file;
    }

    private Microbenchmark unmarshal(File xmlFile)
    {
        Microbenchmark mb = null;
        try
        {
            JAXBContext jc = JAXBContext.newInstance("benchmarkGenerator.xml");
            Unmarshaller unmarshaller = jc.createUnmarshaller();
            //ADD SYNTAX VALIDATION
            mb = (Microbenchmark)unmarshaller.unmarshal(xmlFile);
        }
        catch (JAXBException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }
        return mb;
    }

    private void testPrint()
    {
        try
        {
            VoltTable table = m_voltClient.callProcedure("SelectAll").getResults()[0];
            int numRows = table.getRowCount();
/*
            for (int i = 0; i < numRows; i++)
                System.out.println("ID " + table.fetchRow(i).get(0, table.getColumnType(0)) + " with item " + table.fetchRow(i).get(1, table.getColumnType(1)));
*/
            System.out.println(numRows + " ids total.");
        }
        catch (VoltAbortException e)
        {
            System.out.println("ERROR");
        }
        catch (ConnectException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }
        catch (ProcCallException e)
        {
            System.out.println("Procedure call error: " + e.getMessage());
        }
    }

    private static boolean userTrueFalse(String message, String trueString, String falseString)
    {
        Scanner scan;
        String input = "";
        while (!input.equals(trueString) && !input.equals(falseString))
        {
            scan = new Scanner(System.in);
            System.out.print(message + " (" + trueString + "/" + falseString + "): ");
            input = scan.nextLine();
        }
        if (input.equals(trueString))
            return true;
        else
            return false;
    }
}