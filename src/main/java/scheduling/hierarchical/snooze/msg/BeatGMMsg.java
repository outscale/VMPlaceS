package scheduling.hierarchical.snooze.msg;

import scheduling.hierarchical.snooze.GroupManager;

/**
 * Created by sudholt on 29/06/2014.
 */
public class BeatGMMsg extends SnoozeMsg {
    public BeatGMMsg(GroupManager gm, String sendBox, String origin, String replyBox) {
        super(gm, sendBox, origin, replyBox);
    }
}
