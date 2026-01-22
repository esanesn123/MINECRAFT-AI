package com.primexiter.AIChatBot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AIChatBot extends JavaPlugin implements Listener {

    private String apiKey;
    private String systemPrompt;

    // UPDATED [2026]: Using Gemini 2.5 Flash (The current standard model)
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AIChatBot Enabled! Powered by Gemini 2.5 Flash.");
    }

    @Override
    public void onDisable() {
        getLogger().info("AIChatBot Disabled!");
    }

    private void loadConfigValues() {
        reloadConfig();
        // .trim() prevents copy-paste errors with spaces
        apiKey = getConfig().getString("api_key", "").trim();
        systemPrompt = getConfig().getString("system_prompt", "You are a helpful Minecraft Assistant.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            if (command.getName().equalsIgnoreCase("aichat")) {
                if (args.length == 0) {
                    player.sendMessage("§cUsage: /aichat <message>");
                    return true;
                }
                sendToAI(player, String.join(" ", args));
                return true;
            }
            if (command.getName().equalsIgnoreCase("aichatreload")) {
                if (!player.hasPermission("aichat.admin")) {
                    player.sendMessage("§cYou don't have permission.");
                    return true;
                }
                loadConfigValues();
                player.sendMessage("§aAI Config Reloaded!");
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (event.getMessage().startsWith("!ai ")) {
            event.setCancelled(true);
            if (event.getPlayer().hasPermission("aichat.use")) {
                sendToAI(event.getPlayer(), event.getMessage().substring(4));
            } else {
                event.getPlayer().sendMessage("§cYou do not have permission to use AI.");
            }
        }
    }

    private void sendToAI(Player player, String userMessage) {
        player.sendMessage("§7[AI] Thinking..."); 

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (apiKey == null || apiKey.length() < 30) {
                    Bukkit.getScheduler().runTask(this, () -> player.sendMessage("§cError: Invalid API Key in config.yml!"));
                    return;
                }

                URL url = new URL(GEMINI_API_URL + apiKey);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                // --- Build JSON Payload (Gemini 2.5 Structure) ---
                JsonObject jsonPayload = new JsonObject();
                
                // 1. System Instruction (Native support in 2.5)
                JsonObject systemPart = new JsonObject();
                systemPart.addProperty("text", systemPrompt);
                JsonArray systemPartsArr = new JsonArray();
                systemPartsArr.add(systemPart);
                JsonObject systemInstruction = new JsonObject();
                systemInstruction.add("parts", systemPartsArr);
                jsonPayload.add("systemInstruction", systemInstruction);

                // 2. User Content
                JsonObject userPart = new JsonObject();
                userPart.addProperty("text", userMessage);
                JsonArray userPartsArr = new JsonArray();
                userPartsArr.add(userPart);
                
                JsonObject contentObj = new JsonObject();
                contentObj.addProperty("role", "user");
                contentObj.add("parts", userPartsArr);
                
                JsonArray contentsArr = new JsonArray();
                contentsArr.add(contentObj);
                jsonPayload.add("contents", contentsArr);

                // --- Send Request ---
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                // --- Read Response Code ---
                int responseCode = conn.getResponseCode();
                InputStream stream = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();

                if (stream == null) {
                    Bukkit.getScheduler().runTask(this, () -> player.sendMessage("§cAI Error: Connection failed completely."));
                    return;
                }

                BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder responseRaw = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) responseRaw.append(line);

                // --- Handle Errors ---
                if (responseCode != 200) {
                    getLogger().severe("AI API FAILED (Code " + responseCode + ")");
                    getLogger().severe("GOOGLE RESPONSE: " + responseRaw.toString());
                    
                    Bukkit.getScheduler().runTask(this, () -> player.sendMessage("§cAI Error: " + responseCode + ". Check console for details."));
                    return;
                }

                // --- Parse Success ---
                JsonObject jsonResponse = JsonParser.parseString(responseRaw.toString()).getAsJsonObject();
                
                if (!jsonResponse.has("candidates") || jsonResponse.getAsJsonArray("candidates").size() == 0) {
                     Bukkit.getScheduler().runTask(this, () -> player.sendMessage("§cAI Error: AI returned empty response."));
                     return;
                }

                String aiResponse = jsonResponse.getAsJsonArray("candidates")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("content")
                        .getAsJsonArray("parts")
                        .get(0).getAsJsonObject()
                        .get("text").getAsString();

                // Clean Markdown (**bold** -> §lbold)
                String finalMessage = aiResponse.replace("**", "§l").replace("*", "");

                Bukkit.getScheduler().runTask(this, () -> player.sendMessage("§a[AI] §f" + finalMessage));

            } catch (Exception e) {
                e.printStackTrace();
                Bukkit.getScheduler().runTask(this, () -> player.sendMessage("§cPlugin Error: " + e.getMessage()));
            }
        });
    }
}