package scheduling.hierarchical.snooze.msg;

/**
 * Created by sudholt on 05/07/2014.
 */
public class BeatLCMsg extends SnoozeMsg {
    public BeatLCMsg(double ts, String sendBox, String origin, String replyBox) {
        super(ts, sendBox, origin, replyBox);
    }
}
