package com.katsuyamaki.mychatgpt.notifications;

/** Plain-JDK deep-link regression checks, including project conversation paths. */
public final class ChatLinkPolicySmokeTest {
    private static void same(String raw, String expected) {
        String actual = ChatLinkPolicy.normalize(raw);
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Wrong normalized route: " + raw
                    + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        String a = "https://chatgpt.com/c/12345678-aaaa-bbbb-cccc-123456789012";
        String b = "https://chatgpt.com/g/g-p-project123/c/12345678-aaaa-bbbb-cccc-123456789012";
        same(a, a);
        same(b, b);
        same(a + "/?model=foo#reply", a);
        same("https://CHATGPT.COM/c/12345678-aaaa-bbbb-cccc-123456789012", a);
        same("http://chatgpt.com/c/12345678-aaaa-bbbb-cccc-123456789012", null);
        same("https://chatgpt.com.evil.test/c/12345678-aaaa-bbbb-cccc-123456789012", null);
        same("https://attacker@chatgpt.com/c/12345678-aaaa-bbbb-cccc-123456789012", null);
        same("https://chatgpt.com:8443/c/12345678-aaaa-bbbb-cccc-123456789012", null);
        same("https://chatgpt.com/c/short", null);
        same("https://chatgpt.com/", null);
        same(null, null);
        if (!ChatLinkPolicy.sameChat(a + "?foo=bar", a)) {
            throw new AssertionError("The same chat should not reload");
        }
        if (ChatLinkPolicy.sameChat(a, b)) {
            throw new AssertionError("Project and non-project routes must remain distinct");
        }
        System.out.println("ChatLinkPolicySmokeTest: all assertions passed");
    }
}
