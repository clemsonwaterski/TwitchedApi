/*
 * Copyright (c) 2017 Rolando Islas. All Rights Reserved
 *
 */

package com.rolandoislas.twitchunofficial;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rolandoislas.twitchunofficial.util.ApiCache;
import com.rolandoislas.twitchunofficial.util.AuthUtil;
import com.rolandoislas.twitchunofficial.util.Logger;
import me.philippheuer.twitch4j.TwitchClient;
import me.philippheuer.twitch4j.TwitchClientBuilder;
import me.philippheuer.twitch4j.model.Game;
import me.philippheuer.twitch4j.model.Stream;
import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

import static com.rolandoislas.twitchunofficial.TwitchUnofficial.cache;

public class TwitchUnofficialApi {

    private static final int BAD_REQUEST = 400;
    private static final int SERVER_ERROR =  503;
    private static TwitchClient twitch;
    private static Gson gson;

    /**
     * Send a JSON error message to the current requester
     * @param code HTTP status code
     * @param message error message
     * @return halt
     */
    private static HaltException halt(int code, String message) {
        JsonObject jsonObject = new JsonObject();
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        jsonObject.add("error", error);
        throw Spark.halt(code, jsonObject.toString());
    }

    /**
     * Helper to send 401 unauthorized
     */
    private static void unauthorized() {
        throw halt(401, "Unauthorized");
    }

    /**
     * Helper to check authentication headers
     * @param request request to check
     */
    private static void checkAuth(Request request) {
        if (!AuthUtil.verify(request))
            unauthorized();
    }

    /**
     * Get stream data from twitch
     * @param request request
     * @param response response
     * @return stream data
     */
    static String getStreams(Request request, Response response) {
        checkAuth(request);
        Long limit = null;
        Long offset = null;
        String language;
        Game game = null;
        String channel;
        String streamType;
        try {
            limit = Long.parseLong(request.queryParams("limit"));
        }
        catch (NumberFormatException ignore) {}
        try {
            offset = Long.parseLong(request.queryParams("offset"));
        }
        catch (NumberFormatException ignore) {}
        language = request.queryParams("language");
        String gameString = request.queryParams("game");
        if (gameString != null) {
            game = new Game();
            game.setName(gameString);
        }
        channel = request.queryParams("channel");
        streamType = request.queryParams("type");
        // Check cache
        String requestId = ApiCache.createKey("streams", limit, offset, language, game != null ? game.getName() : null,
                channel, streamType);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;
        // Request live
        List<Stream> streams = twitch.getStreamEndpoint().getAll(
                Optional.ofNullable(limit),
                Optional.ofNullable(offset),
                Optional.ofNullable(language),
                Optional.ofNullable(game),
                Optional.ofNullable(channel),
                Optional.ofNullable(streamType)
        );
        String json = gson.toJson(streams);
        cache.set(requestId, json);
        return json;
    }

    /**
     * Get stream HLS m3u8 links
     * @param request request
     * @param response response
     * @return links
     */
    static String getHlsData(Request request, Response response) {
        checkAuth(request);
        String username = request.queryParams("username");
        if (username == null || username.isEmpty())
            throw halt(BAD_REQUEST, "Missing username query parameter");
        // Check cache
        String requestId = ApiCache.createKey("hls", username);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;
        // Get live data
        // Test Python call
        try {
            Process streamlink = new ProcessBuilder(
                    "streamlink",
                    "--json",
                    String.format("twitch.tv/%s", username)).start();
            streamlink.waitFor();
            Scanner scanner = new Scanner(streamlink.getInputStream()).useDelimiter("\\A");
            if (scanner.hasNext())
                return scanner.next();
        } catch (IOException | InterruptedException e) {
            Logger.warn("Failed to call streamlink");
            Logger.exception(e);
        }
        throw halt(SERVER_ERROR, "Failed to fetch data");
    }

    /**
     * Initialize the Twitch API wrapper
     * @param twitchClientId client id
     * @param twitchClientSecret secret
     * @param twitchToken token
     */
    static void init(String twitchClientId, String twitchClientSecret, String twitchToken) {
        TwitchUnofficialApi.twitch = TwitchClientBuilder.init()
                .withClientId(twitchClientId)
                .withClientSecret(twitchClientSecret)
                .withCredential(twitchToken)
                .build();
        TwitchUnofficialApi.gson = new Gson();
    }
}
