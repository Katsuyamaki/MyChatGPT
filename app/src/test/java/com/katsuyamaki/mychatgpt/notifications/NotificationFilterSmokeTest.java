package com.katsuyamaki.mychatgpt.notifications;

/** Runnable with stock JDK, no Android emulator or JUnit dependency. */
public final class NotificationFilterSmokeTest {
    private static void expect(boolean want, String title, String body) {
        boolean actual = NotificationFilter.isScheduledTaskOccurrence(title, body);
        if (actual != want) {
            throw new AssertionError("Task classification mismatch: " + title
                    + " / " + body + " expected=" + want + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        expect(true, "Scheduled task", "Your scheduled task is complete");
        expect(true, "Your task", "Morning brief is ready");
        expect(true, "Scheduled task Daily Brief", "The task run completed");
        expect(true, "Task", "The scheduled task has failed");
        expect(true, "Reminder: Stand up", "");
        expect(true, "ChatGPT", "Reminder: Hydrate today");
        expect(true, "Scheduled reminder - Today", "");
        expect(true, "Monitoring task", "Result ready");

        expect(false, "Sidebar opened", "Your projects and chats");
        expect(false, "ChatGPT", "Copied to clipboard");
        expect(false, "Download complete", "Saved to Download");
        expect(false, "Copy", "Text copied");
        expect(false, "Task", "Task created successfully");
        expect(false, "Scheduled tasks", "Manage scheduled tasks");
        expect(false, "Task settings", "Schedules updated");
        expect(false, "ChatGPT", "Response complete");
        expect(false, "Project Alpha", "Project settings saved");
        expect(false, "Tasks", "");
        expect(false, "Your tasks", "Open the task dashboard");
        System.out.println("NotificationFilterSmokeTest: all assertions passed");
    }
}
