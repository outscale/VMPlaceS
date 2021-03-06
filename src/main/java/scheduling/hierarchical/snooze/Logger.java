package scheduling.hierarchical.snooze;

import org.simgrid.msg.Msg;

/**
 * Created by sudholt on 29/06/2014.
 */
public class Logger {

    public static void err(String s) {
        if (SnoozeProperties.getInfoLevel() <= 4) Msg.info("ERRSNOO: " + s);
    }

    public static void exc(String s) {
        if (SnoozeProperties.getInfoLevel() <= 4) Msg.info("EXCSNOO: " + s);
    }

    public static void imp(String s) {
        if (SnoozeProperties.getInfoLevel() <= 3) Msg.info("IMPSNOO: " + s);
    }

    public static void info(String s) {
        if (SnoozeProperties.getInfoLevel() <= 2) Msg.info("INFSNOO: " + s);
    }

    public static void debug(String s) {
        if (SnoozeProperties.getInfoLevel() == 1) Msg.info("DEBSNOO: " + s);
    }
    public static void log(Exception e) {
        Msg.info("EXCSNOO: ");
        e.printStackTrace(System.err);
    }
}