package org.opencb.opencga.lib;

import com.google.common.base.Splitter;
import org.opencb.opencga.lib.common.Config;
import org.opencb.opencga.lib.exec.Command;
import org.opencb.opencga.lib.exec.SingleProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.*;

public class SgeManager {


    private static final Map<String, String> stateDic;
    public static final String UNKNOWN = "unknown";
    public static final String RUNNING = "running";
    public static final String TRANSFERRED = "transferred";
    public static final String QUEUED = "queued";
    public static final String ERROR = "error";

    protected static Logger logger = LoggerFactory.getLogger(SgeManager.class);
    private static Properties analysisProperties = Config.getAnalysisProperties();

    static {
        stateDic = new HashMap<String, String>();
        stateDic.put("r", RUNNING);
        stateDic.put("t", TRANSFERRED);
        stateDic.put("qw", QUEUED);
        stateDic.put("Eqw", ERROR);
    }


    public static void queueJob(String toolName, String wumJobId, int wumUserId, String outdir, String commandLine,
                                String queue) throws Exception {
        // init sge job
        String sgeCommandLine = "qsub -V -N " + getSgeJobName(toolName, wumJobId) + " -o " + outdir + "/sge_out.log -e "
                + outdir + "/sge_err.log -q " + queue + " -b y " + commandLine;
        logger.info("SgeManager: Enqueuing job: " + sgeCommandLine);

        // thrown command to shell
        Command sgeCommand = new Command(sgeCommandLine);
        SingleProcess sp = new SingleProcess(sgeCommand);
        sp.getRunnableProcess().run();
    }

    public static void queueJob(String toolName, String wumJobId, int wumUserId, String outdir, String commandLine)
            throws Exception {

        // init sge job
        String sgeCommandLine = "qsub -V -N " + getSgeJobName(toolName, wumJobId) + " -o " + outdir + "/sge_out.log -e "
                + outdir + "/sge_err.log -q " + getQueueName(toolName) + " -b y " + commandLine;
        logger.info("SgeManager: Enqueuing job: " + sgeCommandLine);

        // thrown command to shell
        Command sgeCommand = new Command(sgeCommandLine);
        SingleProcess sp = new SingleProcess(sgeCommand);
        sp.getRunnableProcess().run();
    }

    private static String getSgeJobName(String toolName, String wumJobId) {
        return toolName.replace(" ", "_") + "_" + wumJobId;
    }

    private static String getQueueName(String toolName) throws Exception {
        String defaultQueue = getDefaultQueue();
        logger.debug("SgeManager: default queue: " + defaultQueue);

        // get all available queues
        List<String> queueList = getQueueList();
        logger.debug("SgeManager: available queues: " + queueList);

        // search corresponding queue
        String selectedQueue = defaultQueue;
        String queueProperty;
        for (String queue : queueList) {
            if (!queue.equalsIgnoreCase(defaultQueue)) {
                queueProperty = "OPENCGA.SGE." + queue.toUpperCase() + ".TOOLS";
                if (analysisProperties.containsKey(queueProperty)) {
                    if (belongsTheToolToQueue(analysisProperties.getProperty(queueProperty), toolName)) {
                        selectedQueue = queue;
                    }
                }
            }
        }
        logger.info("SgeManager: selected queue for tool '" + toolName + "': " + selectedQueue);
        return selectedQueue;
    }

    private static String getDefaultQueue() throws Exception {
        if (analysisProperties.containsKey("OPENCGA.SGE.DEFAULT.QUEUE")) {
            return analysisProperties.getProperty("OPENCGA.SGE.DEFAULT.QUEUE");
        } else {
            throw new Exception("OPENCGA.SGE.DEFAULT.QUEUE is not defined!");
        }
    }

    private static List<String> getQueueList() {
        if (analysisProperties.containsKey("OPENCGA.SGE.AVAILABLE.QUEUES")) {
            return Splitter.on(",").splitToList(analysisProperties.getProperty("OPENCGA.SGE.AVAILABLE.QUEUES"));
        } else {
            return new ArrayList<String>();
        }
    }

    private static boolean belongsTheToolToQueue(String tools, String toolName) {
        List<String> toolList = Splitter.on(",").splitToList(tools);
//        List<String> toolList = StringUtils.toList(tools, ",");
        // System.err.println("Tool list : " + toolList);
        return toolList.contains(toolName);
    }

    public static String status(String jobId) throws Exception {
        String status = UNKNOWN;

        String xml = null;
        try {
            Process p = Runtime.getRuntime().exec("qstat -xml");
            StringBuilder stdOut = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));

            String aux = "";
            while ((aux = br.readLine()) != null) {
                stdOut.append(aux);
            }
            xml = stdOut.toString();
            br.close();
        } catch (Exception e) {
            logger.error(e.toString());
            throw new Exception("ERROR: can't get status for job " + jobId + ".");
        }

        if (xml != null) {
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document doc = db.parse(new InputSource(new StringReader(xml)));
                doc.getDocumentElement().normalize();
                NodeList nodeLst = doc.getElementsByTagName("job_list");

                for (int s = 0; s < nodeLst.getLength(); s++) {
                    Node fstNode = nodeLst.item(s);

                    if (fstNode.getNodeType() == Node.ELEMENT_NODE) {
                        Element fstElmnt = (Element) fstNode;
                        NodeList fstNmElmntLst = fstElmnt.getElementsByTagName("JB_name");
                        Element fstNmElmnt = (Element) fstNmElmntLst.item(0);
                        NodeList fstNm = fstNmElmnt.getChildNodes();
                        String jobName = ((Node) fstNm.item(0)).getNodeValue();
                        if (jobName.contains(jobId)) {
                            NodeList lstNmElmntLst = fstElmnt.getElementsByTagName("state");
                            Element lstNmElmnt = (Element) lstNmElmntLst.item(0);
                            NodeList lstNm = lstNmElmnt.getChildNodes();
                            status = ((Node) lstNm.item(0)).getNodeValue();
                        }
                    }
                }
            } catch (Exception e) {
                logger.error(e.toString());
                throw new Exception("ERROR: can't get status for job " + jobId + ".");
            }
        }

        if (!status.equals(UNKNOWN)) {
            status = stateDic.get(status);
        } else {
            String command = "qacct -j " + jobId;
            logger.info(command);
            Process p = Runtime.getRuntime().exec(command);
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            String exitStatus = null;
            String failed = null;
            while ((line = in.readLine()) != null) {
                logger.info(line);
                if (line.contains("exit_status")) {
                    exitStatus = line.replace("exit_status", "").trim();
                }
                if (line.contains("failed")) {
                    failed = line.replace("failed", "").trim();
                }
            }
            p.waitFor();
            in.close();

            if (exitStatus != null && failed != null) {
                if (!"0".equals(failed)) {
                    status = "queue error";
                }
                if ("0".equals(exitStatus)) {
                    status = "finished";
                } else {
                    status = "execution error";
                }
            }
        }
        return status;
    }

}
