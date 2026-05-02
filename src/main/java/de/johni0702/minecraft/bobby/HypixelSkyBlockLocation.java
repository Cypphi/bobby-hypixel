package de.johni0702.minecraft.bobby;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HypixelSkyBlockLocation {
    private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("^Profile ID: ([a-z0-9\\-]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKYBLOCK_TITLE_PATTERN = Pattern.compile("SK[YI]BLOCK(?: .*)?");
    private static final long LOC_RAW_REQUEST_INTERVAL_MS = 15_000;
    private static final long PROFILE_ID_REQUEST_INTERVAL_MS = 10_000;
    private static final Set<String> PROFILE_SCOPED_MODES = Set.of("dynamic", "garden");
    private static final Set<String> SERVER_SCOPED_MODES = Set.of("crystal_hollows", "dungeon", "kuudra", "mineshaft");

    private static String currentServerName = "";
    private static boolean hypixelServer;
    private static LocationKind locationKind = LocationKind.NOT_SKYBLOCK;
    private static String locrawServer = "";
    private static String locrawMode = "";
    private static String locrawMap = "";
    private static String profileId = "";
    private static boolean scoreboardSeenSkyBlock;
    private static boolean scoreboardGuest;
    private static long lastLocrawRequest;
    private static long lastProfileIdRequest;
    private static boolean hideNextLocrawResponse;
    private static boolean hideNextProfileIdResponse;

    private HypixelSkyBlockLocation() {
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register(HypixelSkyBlockLocation::onGameMessage);
    }

    public static void trackServer(String serverName) {
        if (Objects.equals(currentServerName, serverName)) {
            return;
        }
        currentServerName = serverName;
        hypixelServer = isHypixelServerName(serverName);
        locationKind = hypixelServer ? LocationKind.UNKNOWN : LocationKind.NOT_SKYBLOCK;
        locrawServer = "";
        locrawMode = "";
        locrawMap = "";
        profileId = "";
        scoreboardSeenSkyBlock = false;
        scoreboardGuest = false;
        lastLocrawRequest = 0;
        lastProfileIdRequest = 0;
        hideNextLocrawResponse = false;
        hideNextProfileIdResponse = false;
    }

    public static void tick(MinecraftClient client) {
        trackServer(getCurrentServerName(client));
        if (!hypixelServer || client.player == null || client.getNetworkHandler() == null) {
            return;
        }

        updateScoreboardState(client);

        if (locationKind == LocationKind.UNKNOWN) {
            requestLocraw(client);
        } else if (locationKind == LocationKind.SKYBLOCK && PROFILE_SCOPED_MODES.contains(locrawMode) && profileId.isEmpty()) {
            requestProfileId(client);
        }
    }

    public static StorageKey getStorageKey(String serverName) {
        trackServer(serverName);
        if (!hypixelServer || locationKind == LocationKind.NOT_SKYBLOCK) {
            return StorageKey.DEFAULT;
        }
        if (locationKind == LocationKind.UNKNOWN || locrawMode.isEmpty()) {
            return StorageKey.WAITING;
        }

        List<String> segments = new ArrayList<>();
        segments.add("skyblock");
        segments.add(locrawMode);

        if (SERVER_SCOPED_MODES.contains(locrawMode)) {
            segments.add("server");
            segments.add(locrawServer.isEmpty() ? "unknown" : locrawServer);
            if (!locrawMap.isEmpty()) {
                segments.add(locrawMap);
            }
        } else if (PROFILE_SCOPED_MODES.contains(locrawMode)) {
            if (!scoreboardSeenSkyBlock) {
                return StorageKey.WAITING;
            }
            if (scoreboardGuest) {
                segments.add("guest");
                segments.add(locrawServer.isEmpty() ? "unknown" : locrawServer);
            } else {
                if (profileId.isEmpty()) {
                    return StorageKey.WAITING;
                }
                segments.add("profile");
                segments.add(profileId);
            }
        }

        return new StorageKey(false, List.copyOf(segments));
    }

    private static boolean onGameMessage(Text message, boolean overlay) {
        trackServer(getCurrentServerName(MinecraftClient.getInstance()));
        String text = message.getString().trim();
        if (parseLocraw(text)) {
            boolean hide = hideNextLocrawResponse;
            hideNextLocrawResponse = false;
            return !hide;
        }
        if (parseProfileId(text)) {
            boolean hide = hideNextProfileIdResponse;
            hideNextProfileIdResponse = false;
            return !hide;
        }
        return true;
    }

    private static boolean parseLocraw(String text) {
        if (!text.startsWith("{") || !text.endsWith("}") || !text.contains("\"server\"")) {
            return false;
        }

        JsonObject object;
        try {
            object = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return false;
        }
        if (!object.has("server")) {
            return false;
        }

        String server = getString(object, "server");
        String gameType = getString(object, "gametype");
        String mode = getString(object, "mode").toLowerCase(Locale.ROOT);
        String map = getString(object, "map");

        hypixelServer = true;
        if (!Objects.equals(locrawServer, server) || !Objects.equals(locrawMode, mode)) {
            profileId = "";
            scoreboardSeenSkyBlock = false;
            scoreboardGuest = false;
        }
        locrawServer = server;
        locrawMode = mode;
        locrawMap = map;

        if ("SKYBLOCK".equalsIgnoreCase(gameType) && !mode.isEmpty()) {
            locationKind = LocationKind.SKYBLOCK;
        } else {
            locationKind = LocationKind.NOT_SKYBLOCK;
        }
        return true;
    }

    private static boolean parseProfileId(String text) {
        Matcher matcher = PROFILE_ID_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return false;
        }
        profileId = matcher.group(1).toLowerCase(Locale.ROOT);
        return true;
    }

    private static void updateScoreboardState(MinecraftClient client) {
        if (client.world == null) {
            return;
        }
        ScoreboardObjective objective = client.world.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) {
            return;
        }
        String title = objective.getDisplayName().getString().trim().toUpperCase(Locale.ROOT);
        if (!SKYBLOCK_TITLE_PATTERN.matcher(title).matches()) {
            return;
        }
        scoreboardSeenSkyBlock = true;
        scoreboardGuest = title.contains("GUEST");
    }

    private static void requestLocraw(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastLocrawRequest < LOC_RAW_REQUEST_INTERVAL_MS) {
            return;
        }
        lastLocrawRequest = now;
        hideNextLocrawResponse = true;
        sendCommand(client, "locraw");
    }

    private static void requestProfileId(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastProfileIdRequest < PROFILE_ID_REQUEST_INTERVAL_MS) {
            return;
        }
        lastProfileIdRequest = now;
        hideNextProfileIdResponse = true;
        sendCommand(client, "profileid");
    }

    private static void sendCommand(MinecraftClient client, String command) {
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (networkHandler != null) {
            networkHandler.sendChatCommand(command);
        }
    }

    private static String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static String getCurrentServerName(MinecraftClient client) {
        if (client.getServer() != null) {
            return "";
        }
        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo == null && client.getNetworkHandler() != null) {
            serverInfo = client.getNetworkHandler().getServerInfo();
        }
        if (serverInfo == null) {
            return "";
        }
        if (serverInfo.isRealm()) {
            return "realms";
        }
        return serverInfo.address.replace(':', '_');
    }

    private static boolean isHypixelServerName(String serverName) {
        String lower = serverName.toLowerCase(Locale.ROOT);
        return lower.contains("hypixel.net") || lower.contains("hypixel.io");
    }

    private enum LocationKind {
        UNKNOWN,
        NOT_SKYBLOCK,
        SKYBLOCK
    }

    public record StorageKey(boolean waiting, List<String> segments) {
        private static final StorageKey DEFAULT = new StorageKey(false, List.of());
        private static final StorageKey WAITING = new StorageKey(true, List.of());

        public boolean isDefault() {
            return !waiting && segments.isEmpty();
        }

        public String id() {
            if (waiting) {
                return "<waiting>";
            }
            return String.join("/", segments);
        }
    }
}
