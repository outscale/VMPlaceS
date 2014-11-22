package scheduling.snooze;

import configuration.XHost;
import entropy.configuration.Configuration;
import org.simgrid.msg.*;
import org.simgrid.msg.Process;
import scheduling.entropyBased.entropy2.Entropy2RP;
import scheduling.snooze.msg.*;
import simulation.SimulatorManager;

import java.util.*;

/**
 * Created by sudholt on 25/0/2014.
 */
public class GroupManager extends Process {
    private GroupManager thisGM;
    private String name;
    Host host;
    private boolean glDead = false;
    private boolean joining = true;
    private boolean scheduling = false;
    private boolean thisGMToBeStopped = false;
    String glHostname = "";   //@ Make private
    private double glTimestamp;
    Hashtable<String, LCInfo> lcInfo = new Hashtable<String, LCInfo>();  //@ Make private
    // one mailbox per LC: lcHostname+"beat"
    private double procSum;
    private int memSum;
    private String glSummary = "glSummary";
    private String inbox;
    private String gmHeartbeatNew = "gmHeartbeatNew";
    private String gmHeartbeatBeat = "gmHeartbeatBeat";
    private Collection<XHost> managedLCs;
    private ThreadPool newLCPool;

    public GroupManager(Host host, String name, String[] args) {
        super(host, name, args);
        this.host = host;
        this.name = name;
        this.inbox = AUX.gmInbox(host.getName());
    }

    @Override
    public void main(String[] strings) {
        thisGM = this;
        SnoozeMsg m = null;
        boolean success = false;
        int n = 1;

        Logger.imp("[GM.main] GM started: " + host.getName());
        Test.gmsCreated.remove(this);
        Test.gmsJoined.remove(this);
        Test.gmsCreated.put(this.host.getName(), this);

        newJoin();
        while (!SimulatorManager.isEndOfInjection()){
            try {
                if (!thisGMToBeStopped) {
                    m = (SnoozeMsg) Task.receive(inbox, AUX.DeadTimeout);
                    handle(m);
                    glDead();
                    deadLCs();
                    if(SnoozeProperties.shouldISleep()) sleep(AUX.DefaultComputeInterval);
                } else break;
            } catch (HostFailureException e) {
                thisGMToBeStopped = true;
                break;
            } catch (TimeoutException e) {
                glDead();
                deadLCs();
            } catch (Exception e) {
                String cause = e.getClass().getName();
                Logger.err("[GM.main] PROBLEM? Exception: " + host.getName() + ": " + cause);
                e.printStackTrace();
            }
        }
        thisGMToBeStopped=true;
        Logger.err("[GM.main] GM stopped: " + host.getName() + ", " + m);
        Test.gmsCreated.remove(this);
        Test.gmsJoined.remove(this);
    }

    void handle(SnoozeMsg m) throws HostFailureException {
//        Logger.debug("[GM.handle] GMIn: " + m);
        String cs = m.getClass().getSimpleName();

        switch (cs) {
            case "GMElecMsg":
                handleGMElec(m);
                break;
            case "LCChargeMsg":
                handleLCCharge(m);
                break;
            case "TermGMMsg":
                stopThisGM();
                break;
            case "SnoozeMsg":
                Logger.err("[GM(SnoozeMsg)] Unknown message" + m + " on " + host);
                break;

            case "TestFailGMMsg":
                Logger.err("[GM.main] Failure exit: " + host.getName());
                thisGMToBeStopped = true;
                break;
        }
    }


    void handleGMElec(SnoozeMsg m) throws HostFailureException {
        // This GM will be new leader
        String oldGL = glHostname;
        try {
            // Notify LCs and Multicast, stop this GM
            m = new GLElecStopGMMsg(host.getName(), m.getReplyBox(), null, null);
            m.send();
            Logger.debug("[GM(GMElec)] Stop msg: " + m);
            do {
                m = (SnoozeMsg) Task.receive(inbox);
            } while (!m.getClass().getSimpleName().equals("GLElecStopGMMsg"));

            if (!oldGL.isEmpty()) {
                m = new TermGLMsg(host.getName(), AUX.glInbox(glHostname), null, null);
                m.send();
                Logger.debug("[GM(GMElec)] Old GL to be terminated: " + m);
            }
            GroupLeader gl = new GroupLeader(Host.currentHost(), "groupLeader");
            gl.start();
            Test.gl = gl;
            glHostname = gl.getHost().getName();
            Logger.imp("[GM(GMElec)] New leader created on: " + glHostname);

            if (AUX.GLElectionStopGM) stopThisGM();
        } catch (HostFailureException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void handleLCCharge(SnoozeMsg m) {
        try {
            String lcHostname = (String) m.getOrigin();
            if (lcHostname.equals("") || !lcInfo.containsKey(lcHostname)) return;
            LCChargeMsg.LCCharge cs = (LCChargeMsg.LCCharge) m.getMessage();
            LCCharge newCharge = new LCCharge(cs.getProcCharge(), cs.getMemUsed(), cs.getTimestamp());
            lcInfo.put(lcHostname, new LCInfo(newCharge, cs.getTimestamp()));
            Logger.info("[GM(LCCharge)] Charge/beat updated: " + lcHostname
                    + ", " + lcInfo.get(lcHostname).timestamp + ", " + cs.getProcCharge() + ", " + m);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public class RunNewLC implements Runnable {
        public RunNewLC() {};

        @Override
        public void run() {
            NewLCMsg m;
            try {
                m = (NewLCMsg) Task.receive(inbox + "-newLC");
                Logger.debug("[GM.RunNewLC] " + m);
                String lc = (String) m.getMessage();
                double   ts  = Msg.getClock();
                // Init LC charge and heartbeat
                LCInfo    lci = new LCInfo(new LCCharge(0, 0, ts), ts);
                lcInfo.put(lc, lci);
                Logger.info("[GM.RunNewLC] LC stored: " + m);

                // Send acknowledgment
                m = new NewLCMsg(host.getName(), m.getReplyBox(), null, null);
                m.send();
            } catch (TimeoutException e) {
                Logger.exc("[GM.RunNewLC] PROBLEM? Timeout Exception");
            } catch (HostFailureException e) {
                return;
            } catch (Exception e) {
                Logger.exc("[GM.RunNewLC] Exception");
            }
        }
    }

    /**
     * Identify and handle dead LCs
     */
    void deadLCs() {
        if (lcInfo.isEmpty() || joining) return;
        // Identify dead LCs
        HashSet<String> deadLCs = new HashSet<String>();
        for (String lcHostname: lcInfo.keySet()) {
            if (AUX.timeDiff(lcInfo.get(lcHostname).timestamp) > AUX.HeartbeatTimeout) {
                deadLCs.add(lcHostname);
                Logger.err("[GM.deadLCs] Identified: " + lcHostname + ", " + lcInfo.get(lcHostname).timestamp);
            }
        }
        // Remove dead LCs
        lcInfo.keySet().removeAll(deadLCs);
        for (String l: deadLCs) {
            Test.lcsCreated.remove(l);
            Test.lcsJoined.remove(l);
        }
    }

    void glBeats(SnoozeMsg m) {
        String gl = (String) m.getOrigin();
//        Logger.debug("[GM.glBeats] " + glHostname + ", " + gl);
        if (!glHostname.equals(gl)) {
//            Logger.debug("[GM.glBeats] GL initialized or changed: " + glHostname + ", " + gl);
            if (!gl.isEmpty()) {
                glHostname = m.getOrigin();
                Logger.info("[GM.glBeats] Updated: " + glHostname + ", " + m);
                NewGMMsg ms = new NewGMMsg(this, AUX.glInbox(glHostname) + "-newGM", null, null);
                ms.send();

                if (joining) {
                    procSendMyCharge();
                    procScheduling();
                    newLCPool = new ThreadPool(this, RunNewLC.class.getName(), AUX.lcPoolSize);
                    Logger.imp("[GM.glBeats] GM Join finished: " + m + ", LCPool: " + AUX.lcPoolSize);
                    joining = false;
                    Test.noGMJoins++;
                    Test.gmsJoined.remove(this);
                    Test.gmsJoined.put(this.host.getName(), this);
                }
            }
        }
        glTimestamp = (double) m.getMessage();
    }

    void glDead() {
        if (glDead && !joining) {
            Logger.err("[GM.glDead] GL DEAD, promotion: " + glHostname + ", " + glTimestamp + ", " + Msg.getClock());
            glHostname = "";
            triggerGLPromotion();
        }
    }

    void newJoin() {
        SnoozeMsg m;
        joining = true;
        try {
            // Register at Multicast
            m = new NewGMMsg(this, AUX.multicast + "-newGM", null, null);
            m.send();

            // Trigger leader election
            if (AUX.GLElectionForEachNewGM) {
                String glElecMBox = inbox + "-glElec";
                m = new GLElecMsg(false, AUX.multicast, host.getName(), glElecMBox);
                m.send();
                Logger.debug("[GM.newJoin] GL election triggered: " + m);
            }
            Logger.debug("[GM.newJoin] Finished: " + m);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends GM charge summary info to GL
     */
    void procSendMyCharge() {
        try {
            new Process(host, host.getName() + "-gmCharge") {
                public void main(String[] args) throws HostFailureException {
                    while (!thisGMToBeStopped) {
                        try {
                            summaryInfoToGL();
                            BeatGMMsg m = new BeatGMMsg(thisGM, AUX.multicast + "-relayGMBeats", host.getName(), null);
                            m.send();
                            Logger.info("[GM.procSendMyCharge] " + m);
                            sleep(AUX.HeartbeatInterval*1000);
                        } catch (HostFailureException e) {
                            thisGMToBeStopped = true;
                            break;
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
            }.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends beats to multicast group
     */
    void procScheduling() {
        try {
            new Process(host, host.getName() + "-gmScheduling") {
                public void main(String[] args) throws HostFailureException {
                    long period = (SnoozeProperties.getSchedulingPeriodicity()*1000);
                    boolean periodicScheduling = SnoozeProperties.getSchedulingPeriodic();
                    Logger.imp("[GM.procScheduling] periodicScheduling: " + periodicScheduling);

                    while (!thisGMToBeStopped) {
                        long wait = period;
                        long previousDuration = 0;
                        boolean anyViolation = false;
                        try {
                            if (periodicScheduling) {
                                if (wait > 0) Process.sleep(wait);
                            } else
                                Process.sleep(70); // This sleep simulates the communications between the GM and the LC to update the monitoring information (i.e. a pull model)
                            // A push model would have been better but let's keep it simple and stupid ;)
                            // 70 ms correspond to a round trip between GM and LCs.
                            // TODO 70 ms is an arbitrary value, it would be better to get the RTT of the current topology based on the platform file.
                            for (XHost h : getManagedXHosts()) if (!h.isViable()) anyViolation = true;
                            if ((periodicScheduling || (!periodicScheduling && anyViolation))
                                    && !scheduling && !glHostname.isEmpty() && !thisGMToBeStopped && !glDead) {
                                scheduling = true;
                                previousDuration = scheduleVMs(); // previousDuration is in ms.
                                wait = period - previousDuration;
                                scheduling = false;
                            }
                        } catch (HostFailureException e) {
                            thisGMToBeStopped = true;
                            break;
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
            }.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Stop this GM gracefully
     */
    void stopThisGM() {
        try {
            SnoozeMsg m = new TermGMMsg(host.getName(), AUX.multicast, null, null);
            Comm c = m.isend(AUX.multicast);
            c.waitCompletion();
            Test.gmsCreated.remove(this);
            Test.gmsJoined.remove(this);
            Logger.info("[GM.stopThisGM] MUL notified: " + m);
            for (String lc : lcInfo.keySet()) {
                m = new TermGMMsg(host.getName(), AUX.lcInbox(lc), null, null);
                Logger.info("[GM.stopThisGM] LC to be notified: " + m);
                c = m.isend(AUX.lcInbox(lc));
                c.waitCompletion();
                Logger.info("[GM.stopThisGM] LC to rejoin: " + m);
            }
            thisGMToBeStopped = true;
            Logger.imp("[GM.stopThisGM] MUL, LCs to be notified, rejoin");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends GM charge summary info to GL
     */
    void summaryInfoToGL() {
        if (lcInfo.isEmpty() && glHostname.isEmpty()) return;
        updateChargeSummary();
        GMSumMsg.GMSum c = new GMSumMsg.GMSum(procSum, memSum, lcInfo.size(), Msg.getClock());
        if (!glHostname.isEmpty()) {
            GMSumMsg m = new GMSumMsg(c, AUX.glInbox(glHostname)+"-gmPeriodic", host.getName(), null);
            m.send();
        Logger.info("[GM.summaryInfoToGL] " + m+ ", " + Msg.getClock());
        }
    }

    void triggerGLPromotion() {
        String glElecMBox = inbox + "-glElec";
        SnoozeMsg m = new GLElecMsg(false, AUX.multicast, host.getName(), glElecMBox);
        m.send();
        Logger.debug("[GM.triggerGLPromotion] Finished: " + m);
    }

    /**
     * Updates charge summary based on local LC charge info
     */
    void updateChargeSummary() {
        int proc = 0;
        int mem = 0;
        int s = lcInfo.size();
        if (s>0) {
            for (String lcHostname : lcInfo.keySet()) {
                LCInfo lci = lcInfo.get(lcHostname);
                if (lci != null) {
                    proc += lci.charge.procCharge;
                    mem += lci.charge.memUsed;
                }
            }
            proc /= s;
            mem /= s;
        }
        procSum = proc;
        memSum = mem;
        Logger.debug("[GM.updateChargeSummary] " + proc + ", " + mem + ", " + Msg.getClock());
    }



    void receiveHostQuery() {

    }

    void answerHostQuery() {

    }

    void receiveVMQuery() {

    }

    void answerVMQuery() {

    }

    long scheduleVMs() {

        /* Compute and apply the plan */
        Collection<XHost> hostsToCheck = this.getManagedXHosts();
        Entropy2RP scheduler = new Entropy2RP((Configuration) Entropy2RP.ExtractConfiguration(hostsToCheck));
        Entropy2RP.Entropy2RPRes entropyRes = scheduler.checkAndReconfigure(hostsToCheck);
        long previousDuration = entropyRes.getDuration();
        if (entropyRes.getRes() == 0) {
            Msg.info("Reconfiguration ok (duration: " + previousDuration + ")");
        } else if (entropyRes.getRes() == -1) {
            Msg.info("No viable solution (duration: " + previousDuration + ")");
            // TODO Mario, Please check where/how do you want to store numberOfCrash (i.e. when Entropy did not found a solution)
            // numberOfCrash++;
        } else { // res == -2 Reconfiguration has not been correctly performed
            Msg.info("Reconfiguration plan has been broken (duration: " + previousDuration + ")");
            // TODO Mario, please check where/how do you want to store numberOfBrokenPlan (i.e. when some nodes failures prevent to complete tha reconfiguration plan)
            //numberOfBrokenPlan++;
        }
        return previousDuration;
    }


    void sendVMCommandsLC() {

    }

    /**
     * @return the collection of XHost managed by the GM
     */
    public Collection<XHost> getManagedXHosts() {
        Set<String> xhostNames =  lcInfo.keySet();
        LinkedList<XHost> xhosts = new LinkedList<XHost>();
        for (String xName: xhostNames){
            xhosts.add(SimulatorManager.getXHostByName(xName));
        }
        return xhosts;
    }

    /**
     * LC charge info (proc, mem, timestamp)
     */
    class LCCharge {
        double procCharge;
        int memUsed;
        double timeStamp;

        LCCharge(double proc, int mem, double ts) {
            this.procCharge = proc; this.memUsed = mem; this.timeStamp = ts;
        }
    }

    /**
     * LC-related info (charge info, heartbeat, timestamps)
     */
    class LCInfo {
        LCCharge charge;
        double timestamp;

        LCInfo(LCCharge c, double ts) {
            this.charge = c; this.timestamp = ts;
        }
    }
}
