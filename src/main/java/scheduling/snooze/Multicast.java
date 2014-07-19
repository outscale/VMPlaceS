package scheduling.snooze;

import org.simgrid.msg.*;
import org.simgrid.msg.Process;
import scheduling.snooze.msg.*;

import java.util.ArrayList;
import java.util.Hashtable;

/**
 * Created by sudholt on 13/07/2014.
 */
public class Multicast extends Process {
    private String name;
    private Host host;
    private String inbox;
    private String glHostname;
    private double glTimestamp;
    private Hashtable<String, GMInfo> gmInfo = new Hashtable<String, GMInfo>();
    private Hashtable<String, LCInfo> lcInfo = new Hashtable<String, LCInfo>();

    public Multicast(Host host, String name) {
        super(host, name);
        this.host = host;
        this.name = name;
        this.inbox = AUX.multicast;
        this.glHostname = "";
    }

    @Override
    public void main(String[] strings) throws MsgException {
        while (true) {
            SnoozeMsg m = (SnoozeMsg) Task.receive(inbox);
            handle(m);
            sleep(AUX.DefaultComputeInterval);
        }
    }

    /**
     * Receive and relay GL heartbeats
     * @param m
     */
    public void handle(SnoozeMsg m) {
     //   Logger.info("New message :" + m);
        String cs = m.getClass().getSimpleName();
        switch (cs) {
            case "BeatGLMsg": handleBeatGL(m); break;
            case "BeatGMMsg": handleBeatGM(m); break;
            case "GLElecMsg": handleGLElec(m); break;
            case "NewLCMsg" : handleNewLC(m);  break;
            case "NewGMMsg" : handleNewGM(m);  break;
            case "SnoozeMsg":
                Logger.err("[MUL(SnoozeMsg)] Unknown message" + m);
                break;
        }
    }

    void handleBeatGL(SnoozeMsg m) {
        try {
            String gl = (String) m.getMessage();
            if (glHostname == "") {
                glHostname = gl;
                Logger.info("[MUL(BeatGLMsg)] GL initialized: " + gl);
            }
            if (glHostname != gl) {
                Logger.err("[GLH] Multiple GLs: " + glHostname + ", " + gl);
                glHostname = gl;
            }
            glTimestamp = Msg.getClock();
            relayGLBeat();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void handleBeatGM(SnoozeMsg m) {
        String gm = (String) m.getMessage();
        if (gmInfo.get(gm) == null) Logger.err("[MUL(BeatGM)] GM unknown: " + gm);
        else {
            gmInfo.put(gm, new GMInfo(gmInfo.get(gm).replyBox, Msg.getClock()));
            relayGMBeats();
        }
    }

    synchronized void handleGLElec(SnoozeMsg m) {
        if (AUX.timeDiff(glTimestamp) > AUX.HeartbeatTimeout || AUX.GLElectionForEachNewGM) {
            // GL dead
            leaderElection();
        } else {
            // GL still alive
            m = new RBeatGLMsg(glTimestamp, AUX.gmInbox((String) m.getMessage()), glHostname, null);
            Logger.err("[MUL(GLElecMsg)] GL alive, resend: " + m);
        }
    }

    void handleNewLC(SnoozeMsg m) {
//        Logger.info("[MUL(NewLCMsg)] " + m);
        if (!m.getOrigin().equals("removeLCjoinGM")) {
            // Add LC
            lcInfo.put((String) m.getMessage(), new LCInfo((String) m.getMessage(), "", Msg.getClock(), true));
//            Logger.info("[MUL(NewLCMsg)] LC stored: " + m);
        } else {
            // End LC join phase
            lcInfo.put((String) m.getMessage(),
                    new LCInfo((String) m.getMessage(), m.getReplyBox(), Msg.getClock(), false));
//            Logger.info("[MUL(NewLCMsg)] LC removed: " + m);
        }
    }

    void handleNewGM(SnoozeMsg m) {
        gmInfo.put((String) m.getMessage(), new GMInfo(m.getReplyBox(), Msg.getClock()));
        Logger.info("[MUL(NewGMMsg)] GM stored: " + m);
    }

    /**
     * Election of a new GL: promote a GM if possible, create new GL instance
     */
    void leaderElection() {
        if (gmInfo.isEmpty()) {
            // Ex-nihilo GL creation
            GroupLeader gl = new GroupLeader(Host.currentHost(), "groupLeader");
            try {
                gl.start();
            } catch (HostNotFoundException e) {
                e.printStackTrace();
            }
            // Deployment on the Multicast node! Where should it be deployed?
            glHostname = gl.getHost().getName();
            Logger.err("[MUL.leaderElection] New leader ex-nihilo on: " + glHostname);
        } else {
            // Leader election: select GM, send promotion message
            ArrayList<String> gms = new ArrayList<String>(gmInfo.keySet());
            int i = 0;
            boolean success = false;
            String oldGL = "";
            do {
                String gm = gms.get(i % gms.size());
                // Send GL creation request to GM
                SnoozeMsg m = new GMElecMsg(null, AUX.gmInbox(gm), null, null);
                m.send();
                Logger.info("[MUL.leaderElection] GM notified: " + m);

                boolean msgReceived = true;
                try {
//                    m = (SnoozeMsg) Task.receive(AUX.glElection);
                    m = (SnoozeMsg) Task.receive(AUX.glElection, AUX.GLCreationTimeout);
//                    Logger.info("[MUL.leaderElection] Msg: " + m);
                } catch (Exception e) { msgReceived = false; }
                if (msgReceived) {
                    Logger.info("[MUL.leaderElection] GM->GL: " + m);
                    oldGL = glHostname;
                    glHostname = (String) m.getMessage();   // glHostname == gm
                    gmInfo.remove(gm);
                    success = true;
                } else {
                    // Ignore other messages on mbox AUX.glElection
                    Logger.err("[MUL.leaderElection] GM promotion failed: " + gm);
                }
                i++;
            } while (i<10 && !success);
            if (!success) Logger.err("MUL(GLElec)] Leader election failed 10 times");
            new TermGLMsg(name, AUX.glInbox(oldGL), host.getName(), null).send();
            Logger.info("[MUL.leaderElection] New leader elected: " + glHostname);
        }
    }

    /**
     * Relay GL beats to EP, GMs and joining LCs
     */
    void relayGLBeat() {
        if (glHostname != "") {
            new RBeatGLMsg(glTimestamp, AUX.epInbox, glHostname, null).send();
//            Logger.info("[MUL.relayGLbeat] Beat relayed to: " + AUX.epInbox);
            for (String gm : gmInfo.keySet()) {
                new RBeatGLMsg(glTimestamp, gmInfo.get(gm).replyBox, glHostname, null).send();
//                Logger.info("[MUL.relayGLbeat] Beat relayed to GM: " + gmInfo.get(gm).replyBox);
            }
            for (String lc : lcInfo.keySet()) {
                LCInfo lv = lcInfo.get(lc);
                if (lv.join) {
                    SnoozeMsg m = new RBeatGLMsg(glTimestamp, AUX.lcInbox(lv.lcHost), glHostname, null);
                    m.send();
//                    Logger.info("[MUL.relayGLBeats] To LC: " + m);
                }
            }
        } else Logger.err("[MUL] No GL");
//        Logger.info("[MUL.relayGLbeat] GL beat received/relayed: " + glTimestamp);
    }

    /**
     * Relay GM beats to LCs
     */
    void relayGMBeats() {
        for (String lc: lcInfo.keySet()) {
            LCInfo lv = lcInfo.get(lc);
            String gm = lv.gmHost;
            GMInfo gv = gmInfo.get(gm);
            if (!lv.join && gv != null) {
                SnoozeMsg m = new RBeatGMMsg(gv.timestamp, AUX.lcInbox(lv.lcHost), gm, null);
                m.send();
//                Logger.info("[MUL.relayGMBeats] To LC: " + m);
            }
        }
    }

    class GMInfo {
        String replyBox;
        double timestamp;

        GMInfo(String rb, double ts) {
            this.replyBox = rb; this.timestamp = ts;
        }
    }

    class LCInfo {
        String lcHost;
        String gmHost;
        double timestamp;
        boolean join;

        LCInfo(String lc, String gm, double ts, boolean join) {
            this.lcHost = lc; this.gmHost = gm; this.timestamp = ts; this.join = join;
        }
    }
}
