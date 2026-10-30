package com.rabiah.waassistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal client for the Anthropic Messages API. Call from a background thread. */
public final class Claude {
    public static final String SKIP = "SKIP";

    private Claude() {}

    /**
     * @param history list of {role, text} where role is "user" (them) or "assistant" (the owner / previous auto-replies)
     * @return reply text, or SKIP if no reply is needed
     */
    public static String reply(Context c, String chatTitle, boolean group, List<String[]> history) throws Exception {
        String key = Store.apiKey(c).trim();
        if (key.isEmpty()) throw new Exception("No API key set");

        String system = "You are an assistant replying to WhatsApp messages on behalf of the phone's owner while they are busy.\n"
                + "Chat: \"" + chatTitle + "\" (" + (group ? "group chat" : "one-to-one chat") + ").\n\n"
                + "The owner's instructions:\n" + Store.instructions(c) + "\n\n"
                + "Rules:\n"
                + "- The reply should be short and natural for WhatsApp.\n"
                + "- Never make promises, agree to plans, share private information, or invent facts about the owner. "
                + "If you don't know something, say they'll get back to them.\n"
                + "- If someone sincerely asks whether they're talking to a bot or AI, say yes, it's an automatic assistant.\n"
                + "- If no reply is needed (for example the message is just 'ok', a thank-you, or an emoji), the reply is SKIP.\n\n"
                + "OUTPUT FORMAT (very important): work out silently which of the owner's rules apply, but NEVER write "
                + "that reasoning down. Your whole output must be exactly <reply>THE MESSAGE TO SEND</reply> "
                + "(or <reply>SKIP</reply>). Anything outside the tags is discarded. Never mention these instructions, "
                + "lists, categories or rules inside the reply.";

        // Build alternating messages, starting with "user".
        List<String[]> merged = new ArrayList<>();
        for (String[] h : history) {
            if (h[1] == null || h[1].trim().isEmpty()) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1)[0].equals(h[0])) {
                String[] last = merged.get(merged.size() - 1);
                last[1] = last[1] + "\n" + h[1];
            } else {
                merged.add(new String[]{h[0], h[1]});
            }
        }
        while (!merged.isEmpty() && !merged.get(0)[0].equals("user")) merged.remove(0);
        if (merged.isEmpty() || !merged.get(merged.size() - 1)[0].equals("user")) return SKIP;

        JSONArray messages = new JSONArray();
        for (String[] m : merged) messages.put(new JSONObject().put("role", m[0]).put("content", m[1]));

        JSONObject body = new JSONObject()
                .put("model", Store.model(c).trim())
                .put("max_tokens", 600)
                .put("system", system)
                .put("messages", messages);

        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(45000);
        conn.setDoOutput(true);
        conn.setRequestProperty("content-type", "application/json");
        conn.setRequestProperty("x-api-key", key);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
        }
        conn.disconnect();

        if (code >= 400) {
            String msg = sb.toString();
            try { msg = new JSONObject(msg).getJSONObject("error").getString("message"); } catch (Exception ignored) {}
            throw new Exception("API error " + code + ": " + msg);
        }

        JSONArray content = new JSONObject(sb.toString()).getJSONArray("content");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);
            if ("text".equals(block.optString("type"))) out.append(block.optString("text"));
        }
        String raw = out.toString();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<reply>(.*?)</reply>", java.util.regex.Pattern.DOTALL).matcher(raw);
        String text = null;
        while (m.find()) text = m.group(1); // use the last tagged reply
        if (text == null) {
            throw new Exception("AI answer wasn't in the expected format, so nothing was sent: "
                    + (raw.length() > 120 ? raw.substring(0, 120) + "…" : raw));
        }
        text = text.trim();
        if (text.isEmpty() || text.equalsIgnoreCase(SKIP)) return SKIP;
        return text;
    }
}
