package com.katsuyamaki.mychatgpt.notifications;

import java.util.regex.Pattern;

/**
 * Notification feed allowlist. Routine webpage and native toasts are not
 * notification events. The only auto-posted events are finished chat replies
 * and scheduled-task occurrences. This classifier covers task notices from
 * either the website or the optional official-app notification relay.
 *
 * Keep this Android-free so CI can run regression tests with plain javac.
 */
public final class NotificationFilter {
    private static final Pattern TASK_CONTEXT = Pattern.compile(
            "\\b(?:scheduled\\s+tasks?|your\\s+tasks?|tasks?\\s+(?:run|result|reminder)|"
            + "reminders?|reminding\\s+you|monitoring\\s+task|automation\\s+task|"
            + "tasks?\\s+(?:completed?|finished|ready|due|failed|triggered))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_EVENT = Pattern.compile(
            "\\b(?:completed?|finished|ready|ran|run|executed|triggered|fired|"
            + "delivered|due|occurred|failed|failure|needs\\s+attention|result)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMINDER = Pattern.compile(
            "^(?:reminder|scheduled\\s+reminder)\\s*[:\\-—]\\s*\\S",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUTINE = Pattern.compile(
            "\\b(?:copied|clipboard|paste|pasted|download|downloaded|"
            + "saved\\s+to|sidebar|navigation|menu\\s+opened)\\b",
            Pattern.CASE_INSENSITIVE);

    private NotificationFilter() {}

    /**
     * Conservative by design: a bare schedule/task settings or sidebar item
     * isn't a task occurrence; there must also be an event/result cue.
     * Some localized or unlabeled scheduled notices might be missed until
     * we can validate the actual source markup on the user's device.
     */
    public static boolean isScheduledTaskOccurrence(String title, String body) {
        String raw = (title == null ? "" : title) + " " + (body == null ? "" : body);
        if (raw.length() > 2400) return false;
        String value = raw.replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) return false;
        // In official Android notifications, "ChatGPT" is often the title
        // and the reminder itself is in the notification body.
        if (REMINDER.matcher(title == null ? "" : title.trim()).find()
                || REMINDER.matcher(body == null ? "" : body.trim()).find()) {
            return true;
        }
        if (ROUTINE.matcher(value).find()) return false;
        return TASK_CONTEXT.matcher(value).find()
                && TASK_EVENT.matcher(value).find();
    }
}
