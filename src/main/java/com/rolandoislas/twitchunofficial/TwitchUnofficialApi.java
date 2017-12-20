/*
 * Copyright (c) 2017 Rolando Islas. All Rights Reserved
 *
 */

package com.rolandoislas.twitchunofficial;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.rolandoislas.twitchunofficial.data.Id;
import com.rolandoislas.twitchunofficial.data.annotation.Cached;
import com.rolandoislas.twitchunofficial.data.annotation.NotCached;
import com.rolandoislas.twitchunofficial.util.ApiCache;
import com.rolandoislas.twitchunofficial.util.AuthUtil;
import com.rolandoislas.twitchunofficial.util.Logger;
import com.rolandoislas.twitchunofficial.util.twitch.Token;
import com.rolandoislas.twitchunofficial.util.twitch.helix.Follow;
import com.rolandoislas.twitchunofficial.util.twitch.helix.FollowList;
import com.rolandoislas.twitchunofficial.util.twitch.helix.Game;
import com.rolandoislas.twitchunofficial.util.twitch.helix.GameList;
import com.rolandoislas.twitchunofficial.util.twitch.helix.GameViewComparator;
import com.rolandoislas.twitchunofficial.util.twitch.helix.Pagination;
import com.rolandoislas.twitchunofficial.util.twitch.helix.User;
import com.rolandoislas.twitchunofficial.util.twitch.helix.UserList;
import com.rolandoislas.twitchunofficial.util.twitch.helix.UserName;
import me.philippheuer.twitch4j.TwitchClient;
import me.philippheuer.twitch4j.TwitchClientBuilder;
import me.philippheuer.twitch4j.auth.model.OAuthCredential;
import me.philippheuer.twitch4j.enums.Endpoints;
import me.philippheuer.twitch4j.exceptions.RestException;
import me.philippheuer.twitch4j.model.Channel;
import me.philippheuer.twitch4j.model.Community;
import me.philippheuer.twitch4j.model.CommunityList;
import me.philippheuer.twitch4j.model.Stream;
import me.philippheuer.twitch4j.model.TopGame;
import me.philippheuer.twitch4j.model.TopGameList;
import me.philippheuer.util.rest.HeaderRequestInterceptor;
import me.philippheuer.util.rest.QueryRequestInterceptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.rolandoislas.twitchunofficial.TwitchUnofficial.cache;

public class TwitchUnofficialApi {

    static final int BAD_REQUEST = 400;
    static final int SERVER_ERROR =  500;
    static final int BAD_GATEWAY = 502;
    private static final String API = "https://api.twitch.tv/helix";
    private static final String API_HLS_TOKEN = "http://api.twitch.tv/api/channels/%s/access_token";
    private static final String API_HLS_PLAYLIST = "http://usher.twitch.tv/api/channel/hls/%s.m3u8";
    static TwitchClient twitch;
    static Gson gson;
    private static OAuthCredential twitchOauth;

    /**
     * Send a JSON error message to the current requester
     * @param code HTTP status code
     * @param message error message
     * @return halt
     */
    @NotCached
    static HaltException halt(int code, String message) {
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
    @NotCached
    private static void unauthorized() {
        throw halt(401, "Unauthorized");
    }

    /**
     * Helper to check authentication headers
     * @param request request to check
     */
    @NotCached
    static void checkAuth(Request request) {
        if (!AuthUtil.verify(request))
            unauthorized();
    }

    /**
     * Get stream data from twitch
     * @param request request
     * @param response response
     * @return stream data
     */
    @Cached
    static String getStreamsKraken(Request request, Response response) {
        checkAuth(request);
        Long limit = null;
        Long offset = null;
        String language;
        me.philippheuer.twitch4j.model.Game game = null;
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
            game = new me.philippheuer.twitch4j.model.Game();
            game.setName(gameString);
        }
        channel = request.queryParams("channel");
        streamType = request.queryParams("type");
        // Check cache
        String requestId = ApiCache.createKey("kraken/streams", limit, offset, language, game != null ? game.getName() : null,
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
        if (streams == null)
            throw halt(BAD_GATEWAY, "Bad Gateway: Could not connect to Twitch API");
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
    @Cached
    static String getHlsData(Request request, Response response) {
        checkAuth(request);
        if (request.splat().length < 1)
            return null;
        String fileName = request.splat()[0];
        String[] split = fileName.split("\\.");
        if (split.length < 2 || !split[1].equals("m3u8"))
            return null;
        String username = split[0];
        // Check cache
        String requestId = ApiCache.createKey("hls", username);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;
        // Get live data

        // Construct template
        RestTemplate restTemplate = twitch.getRestClient().getRestTemplate();

        // Request channel token
        String hlsTokenUrl = String.format(API_HLS_TOKEN, username);
        ResponseEntity<String> tokenResponse = null;
        try {
            tokenResponse = restTemplate.exchange(hlsTokenUrl, HttpMethod.GET, null,
                    String.class);
        }
        catch (RestException e) {
            throw halt(404, "Streamer not found");
        }
        Token token;
        try {
            token = gson.fromJson(tokenResponse.getBody(), Token.class);
            if (token.getToken() == null || token.getSig() == null)
                throw halt(SERVER_ERROR, "Invalid data: Twitch API may have changed");
        }
        catch (JsonSyntaxException e) {
            throw halt(BAD_GATEWAY, "Failed to parse token data.");
        }

        // Request HLS playlist
        String hlsPlaylistUrl = String.format(API_HLS_PLAYLIST, username);
        restTemplate.getInterceptors().add(new HeaderRequestInterceptor("Accept", "*/*"));
        restTemplate.getInterceptors().add(new QueryRequestInterceptor("player", "twitchunofficialroku"));
        restTemplate.getInterceptors().add(new QueryRequestInterceptor("token", token.getToken()));
        restTemplate.getInterceptors().add(new QueryRequestInterceptor("sig", token.getSig()));
        restTemplate.getInterceptors().add(new QueryRequestInterceptor("p",
                String.valueOf((int)(Math.random() * Integer.MAX_VALUE))));
        restTemplate.getInterceptors().add(new QueryRequestInterceptor("type", "any"));
        restTemplate.getInterceptors().add(new QueryRequestInterceptor("$allow_audio_only", "false"));
        restTemplate.getInterceptors().add(new QueryRequestInterceptor("$allow_source", "false"));
        ResponseEntity<String> playlist = restTemplate.exchange(hlsPlaylistUrl, HttpMethod.GET, null,
                String.class);

        // Cache and return
        String playlistString = playlist.getBody();
        if (playlistString == null)
            return null;
        cache.set(requestId, playlistString);
        response.type("audio/mpegurl");
        return playlistString;
    }

    /**
     * Initialize the Twitch API wrapper
     * @param twitchClientId client id
     * @param twitchClientSecret secret
     * @param twitchToken token
     */
    @NotCached
    static void init(String twitchClientId, String twitchClientSecret, String twitchToken) {
        TwitchUnofficialApi.twitch = TwitchClientBuilder.init()
                .withClientId(twitchClientId)
                .withClientSecret(twitchClientSecret)
                .withCredential(twitchToken)
                .build();
        TwitchUnofficialApi.gson = new Gson();
        // Set credential
        for (Map.Entry<String, OAuthCredential>credentialEntry :
                twitch.getCredentialManager().getOAuthCredentials().entrySet()) {
            twitchOauth = credentialEntry.getValue();
        }
        if (twitchOauth == null)
            Logger.warn("No Oauth token provided. Requests will be rate limited to 30 per minute.");
    }

    /**
     * Get a list of games
     * @param request request
     * @param response response
     * @return games json
     */
    @Cached
    static String getTopGamesKraken(Request request, Response response) {
        checkAuth(request);
        // Parse parameters
        String limit = request.queryParamOrDefault("limit", "10");
        String offset = request.queryParamOrDefault("offset", "0");
        // Check cache
        String requestId = ApiCache.createKey("kraken/games/top", limit, offset);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;

        // Request
        @Nullable List<TopGame> games = getTopGamesKraken(limit, offset);

        if (games == null)
            throw halt(BAD_GATEWAY, "Bad Gateway: Could not connect to Twitch API");

        // Store and return
        String json = gson.toJson(games);
        cache.set(requestId, json);
        return json;
    }

    /**
     * Request top games from the Kraken end point
     * @param limit limit
     * @param offset offset
     * @return top games
     */
    @Nullable
    @NotCached
    private static List<TopGame> getTopGamesKraken(@Nullable String limit, @Nullable String offset) {
        // Fetch live data
        String requestUrl = String.format("%s/games/top", Endpoints.API.getURL());
        RestTemplate restTemplate = twitch.getRestClient().getRestTemplate();
        if (limit != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("limit", limit));
        if (offset != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("offset", offset));
        // REST Request
        List<TopGame> games = null;
        try {
            Logger.verbose( "Rest Request to [%s]", requestUrl);
            TopGameList responseObject = restTemplate.getForObject(requestUrl, TopGameList.class);
            if (responseObject != null)
                games = responseObject.getTop();
        }
        catch (RestClientException | RestException e) {
            Logger.warn("Request failed: " + e.getMessage());
            Logger.exception(e);
        }
        return games;
    }

    /**
     * Get top communities
     * @param request request
     * @param response response
     * @return communities json
     */
    @Cached
    static String getCommunitiesKraken(Request request, Response response) {
        checkAuth(request);
        // Params
        Long limit = null;
        String cursor;
        try {
            limit = Long.parseLong(request.queryParams("limit"));
        }
        catch (NumberFormatException ignore) {}
        cursor = request.queryParams("cursor");
        // Check cache
        String requestId = ApiCache.createKey("kraken/communities/top", limit, cursor);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;
        // Request live
        CommunityList communities = twitch.getCommunityEndpoint()
                .getTopCommunities(Optional.ofNullable(limit), Optional.ofNullable(cursor));
        if (communities == null)
            throw halt(BAD_GATEWAY, "Bad Gateway: Could not connect to Twitch API");
        String json = gson.toJson(communities);
        cache.set(requestId, json);
        return json;
    }

    /**
     * Get a specified community
     * @param request request
     * @param response response
     * @return community json
     */
    @Cached
    static String getCommunityKraken(Request request, Response response) {
        checkAuth(request);
        // Params
        String name = request.queryParams("name");
        String id = request.queryParams("id");
        if ((name == null || name.isEmpty()) && (id == null || id.isEmpty()))
            throw halt(BAD_REQUEST, "Bad Request: name or id is required");
        // Check cache
        String requestId = ApiCache.createKey("kraken/communities", name);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;
        // Request live
        Community community;
        if (name != null && !name.isEmpty())
            community = twitch.getCommunityEndpoint().getCommunityByName(name);
        else
            community = twitch.getCommunityEndpoint().getCommunityById(id);
        String json = gson.toJson(community);
        cache.set(requestId, json);
        return json;
    }

    /**
     * Get streams from the helix endpoint
     * @param request request
     * @param response response
     * @return stream json with usernames added to each stream as "user_name"
     */
    @Cached
    static String getStreamsHelix(Request request, Response response) {
        checkAuth(request);
        // Params
        String after = request.queryParams("after");
        String before = request.queryParams("before");
        String community = request.queryParams("community_id");
        String first = request.queryParams("first");
        String game = request.queryParams("game_id");
        String language = request.queryParams("language");
        String streamType = request.queryParamOrDefault("type", "all");
        String userId = request.queryParams("user_id");
        String userLogin = request.queryParams("user_login");
        // Non-spec params
        String offset = request.queryParams("offset");
        if (first == null)
            first = request.queryParamOrDefault("limit", "20");
        // Set after based on offset
        String afterFromOffset = getAfterFromOffset(offset, first);
        if (afterFromOffset != null)
            after = afterFromOffset;
        // Check cache
        String requestId = ApiCache.createKey("helix/streams", after, before, community, first, game, language,
                streamType, userId, userLogin, offset);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;

        // Request live
        List<com.rolandoislas.twitchunofficial.util.twitch.helix.Stream> streams = getStreams(
                after,
                before,
                Collections.singletonList(community),
                first,
                Collections.singletonList(game),
                Collections.singletonList(language),
                streamType,
                Collections.singletonList(userId),
                Collections.singletonList(userLogin)
        );

        // Cache and return
        String json = gson.toJson(streams);
        cache.set(requestId, json);
        return json;
    }

    /**
     * Get streams from the helix end point
     * @param after cursor
     * @param before cursor
     * @param communities community ids
     * @param first limit
     * @param games game ids
     * @param languages lang ids
     * @param streamType stream type
     * @param userIdsParam user ids
     * @param userLoginsParam user logins
     * @return streams
     */
    @NotNull
    @NotCached
    private static List<com.rolandoislas.twitchunofficial.util.twitch.helix.Stream> getStreams(
            @Nullable String after,
            @Nullable String before,
            @Nullable List<String> communities,
            @Nullable String first,
            @Nullable List<String> games,
            @Nullable List<String> languages,
            @Nullable String streamType,
            @Nullable List<String> userIdsParam,
            @Nullable List<String> userLoginsParam) {
        // Request live

        List<com.rolandoislas.twitchunofficial.util.twitch.helix.Stream> streams = null;
        // Endpoint
        String requestUrl = String.format("%s/streams", API);
        RestTemplate restTemplate;
        if (twitchOauth != null)
            restTemplate = getPrivilegedRestTemplate(twitchOauth);
        else
            restTemplate = twitch.getRestClient().getRestTemplate();
        // Parameters
        if (after != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("after", after));
        if (before != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("before", before));
        if (communities != null)
            for (String community : communities)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("community_id", community));
        if (first != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("first", first));
        if (games != null)
            for (String game : games)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("game_id", game));
        if (languages != null)
            for (String language : languages)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("language", language));
        if (streamType != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("type", streamType));
        if (userIdsParam != null)
            for (String userId : userIdsParam)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("user_id", userId));
        if (userLoginsParam != null)
            for (String userLogin : userLoginsParam)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("user_login", userLogin));
        // REST Request
        try {
            Logger.verbose( "Rest Request to [%s]", requestUrl);
            ResponseEntity<String> responseObject = restTemplate.exchange(requestUrl, HttpMethod.GET, null,
                    String.class);
            try {
                com.rolandoislas.twitchunofficial.util.twitch.helix.StreamList streamList = gson.fromJson(
                        responseObject.getBody(),
                        com.rolandoislas.twitchunofficial.util.twitch.helix.StreamList.class);
                streams = streamList.getStreams();
            }
            catch (JsonSyntaxException ignore) {}
        }
        catch (RestClientException | RestException e) {
            if (e instanceof RestException)
                Logger.warn("Request failed: " + ((RestException) e).getRestError().getMessage());
            else
                Logger.warn("Request failed: " + e.getMessage());
            Logger.exception(e);
        }
        if (streams == null)
            throw halt(BAD_GATEWAY, "Bad Gateway: Could not connect to Twitch API");

        // Add user names and game names to data
        List<String> gameIds = new ArrayList<>();
        List<String> userIds = new ArrayList<>();
        for (com.rolandoislas.twitchunofficial.util.twitch.helix.Stream stream : streams) {
            userIds.add(stream.getUserId());
            gameIds.add(stream.getGameId());
        }
        Map<String, String> userNames = getUserNames(userIds);
        Map<String, String> gameNames = getGameNames(gameIds);
        for (com.rolandoislas.twitchunofficial.util.twitch.helix.Stream stream : streams) {
            String userName = userNames.get(stream.getUserId());
            try {
                stream.setUserName(userName == null ? new UserName() : gson.fromJson(userName, UserName.class));
            }
            catch (JsonSyntaxException e) {
                Logger.exception(e);
                stream.setUserName(new UserName());
            }
            String gameName = gameNames.get(stream.getGameId());
            stream.setGameName(gameName == null ? "" : gameName);
        }

        return streams;
    }

    /**
     * Get a rest templates with the oauth token added as a bearer token
     * @param oauth token to add to header
     * @return rest templates
     */
    private static RestTemplate getPrivilegedRestTemplate(OAuthCredential oauth) {
        RestTemplate restTemplate = twitch.getRestClient().getPlainRestTemplate();
        restTemplate.getInterceptors().add(new HeaderRequestInterceptor("Authorization",
                String.format("Bearer %s", oauth.getToken())));
        return restTemplate;
    }

    /**
     * Get game name for ids, checking the cache first
     * @param gameIds ids
     * @return game name(value) and id(key)
     */
    @Cached
    private static Map<String, String> getGameNames(List<String> gameIds) {
        return getNameForIds(gameIds, Id.GAME);
    }

    /**
     * Get username for ids, checking the cache first
     * @param userIds ids
     * @return user names(value) and ids(key)
     */
    @Cached
    private static Map<String, String> getUserNames(List<String> userIds) {
        return getNameForIds(userIds, Id.USER);
    }

    /**
     * Get user name or game name for ids
     * @param ids user or game id - must be all one type
     * @param type type of ids
     * @return map <id, @Nullable name> all ids passed will be returned
     */
    @Cached
    private static Map<String, String> getNameForIds(List<String> ids, Id type) {
        // Get ids in cache
        Map<String, String> nameIdMap;
        switch (type) {
            case USER:
                nameIdMap = cache.getUserNames(ids);
                break;
            case GAME:
                nameIdMap = cache.getGameNames(ids);
                break;
            default:
                throw new IllegalArgumentException("Id type must be GAME or USER");
        }
        // Find missing ids
        List<String> missingIds = new ArrayList<>();
        for (Map.Entry<String, String> nameId : nameIdMap.entrySet())
            if (nameId.getValue() == null)
                missingIds.add(nameId.getKey());
        if (missingIds.size() == 0)
            return nameIdMap;
        // Request missing ids
        if (type.equals(Id.USER)) {
            List<User> users = getUsers(ids, null, null);
            if (users == null)
                throw halt(BAD_GATEWAY, "Bad Gateway: Could not connect to Twitch API");
            // Store missing ids
            for (User user : users) {
                JsonObject name = new JsonObject();
                name.addProperty("display_name", user.getDisplayName());
                name.addProperty("login", user.getLogin());
                nameIdMap.put(user.getId(), name.toString());
            }
            cache.setUserNames(nameIdMap);
        }
        else if (type.equals(Id.GAME)) {
            List<Game> games = getGames(ids, null);
            if (games == null)
                throw halt(BAD_GATEWAY, "Bad Gateway: Could not connect to Twitch API");
            // Store missing ids
            for (Game game : games)
                nameIdMap.put(game.getId(), game.getName());
            cache.setGameNames(nameIdMap);
        }
        return nameIdMap;
    }

    /**
     * Get games from Twitch API
     * @param ids id of games to fetch
     * @return games
     */
    @NotCached
    @Nullable
    private static List<Game> getGames(@Nullable List<String> ids, @Nullable List<String> names) {
        if ((ids == null || ids.isEmpty()) && (names == null || names.isEmpty()))
            throw halt(BAD_REQUEST, "Bad request: missing game id or name");
        // Request live
        List<Game> games = null;
        // Endpoint
        String requestUrl = String.format("%s/games", API);
        RestTemplate restTemplate;
        if (twitchOauth != null)
            restTemplate = getPrivilegedRestTemplate(twitchOauth);
        else
            restTemplate = twitch.getRestClient().getRestTemplate();
        // Parameters
        if (ids != null)
            for (String id : ids)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("id", id));
        if (names != null)
            for (String name : names)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("name", name));
        // REST Request
        try {
            Logger.verbose( "Rest Request to [%s]", requestUrl);
            ResponseEntity<String> responseObject = restTemplate.exchange(requestUrl, HttpMethod.GET, null, String.class);
            try {
                GameList gameList = gson.fromJson(responseObject.getBody(), GameList.class);
                games = gameList.getGames();
            }
            catch (JsonSyntaxException ignore) {}
        }
        catch (RestClientException | RestException e) {
            if (e instanceof RestException)
                Logger.warn("Request failed: " + ((RestException) e).getRestError().getMessage());
            else
                Logger.warn("Request failed: " + e.getMessage());
            Logger.exception(e);
        }
        return games;
    }

    /**
     * Get a users from Twitch API
     * @param userIds id to poll
     * @param userNames name to poll
     * @param token oauth token to use instead of names or ids. if names or ids are not null, the token is ignored
     * @return list of users
     */
    @NotCached
    private static List<User> getUsers(@Nullable List<String> userIds, @Nullable List<String> userNames,
                                       @Nullable String token) {
        if ((userIds == null || userIds.isEmpty()) && (userNames == null || userNames.isEmpty()) && token == null)
            throw halt(BAD_REQUEST, "Bad request: missing user id or user name");
        // Request live
        List<User> users = null;
        // Endpoint
        String requestUrl = String.format("%s/users", API);
        RestTemplate restTemplate;
        if (token != null) {
            OAuthCredential oauth = new OAuthCredential(token);
            restTemplate = getPrivilegedRestTemplate(oauth);
        }
        else if (twitchOauth != null)
            restTemplate = getPrivilegedRestTemplate(twitchOauth);
        else
            restTemplate = twitch.getRestClient().getRestTemplate();
        // Parameters
        if (userIds != null)
            for (String id : userIds)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("id", id));
        if (userNames != null)
            for (String name : userNames)
                restTemplate.getInterceptors().add(new QueryRequestInterceptor("login", name));
        // REST Request
        try {
            Logger.verbose( "Rest Request to [%s]", requestUrl);
            ResponseEntity<String> responseObject = restTemplate.exchange(requestUrl, HttpMethod.GET, null, String.class);
            try {
                UserList userList = gson.fromJson(responseObject.getBody(), UserList.class);
                users = userList.getUsers();
            }
            catch (JsonSyntaxException ignore) {}
        }
        catch (RestClientException | RestException e) {
            if (e instanceof RestException)
                Logger.warn("Request failed: " + ((RestException) e).getRestError().getMessage());
            else
                Logger.warn("Request failed: " + e.getMessage());
            Logger.exception(e);
        }
        return users;
    }

    /**
     * Get games from the helix endpoint
     * @param request request
     * @param response response
     * @return game json
     */
    @Cached
    static String getGamesHelix(Request request, Response response) {
        checkAuth(request);
        // Parse query params
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String[] queryParams = request.queryString() != null ? request.queryString().split("&") : new String[0];
        for (String queryParam : queryParams) {
            String[] keyValue = queryParam.split("=");
            if (keyValue.length > 2 || keyValue.length < 1)
                throw halt(BAD_REQUEST, "Bad query string");
            if (keyValue.length > 1) {
                String value = keyValue[1];
                try {
                    value = URLDecoder.decode(value, "utf-8");
                }
                catch (UnsupportedEncodingException e) {
                    Logger.exception(e);
                    throw halt(SERVER_ERROR, "Failed to decode params");
                }
                if (!ids.contains(value) && keyValue[0].equals("id"))
                    ids.add(value);
                else if (!names.contains(value) && keyValue[0].equals("name"))
                    names.add(value);
            }
        }

        // Check cache
        ArrayList<String> req = new ArrayList<>();
        req.addAll(ids);
        req.addAll(names);
        String requestId = ApiCache.createKey("helix/games", req.toArray());
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;

        // Fetch live data
        List<Game> games = getGames(ids, names);
        String json = gson.toJson(games);
        cache.set(requestId, json);
        return json;
    }

    /**
     * Get streams that are online and that a user follows
     * @param request request
     * @param response response
     * @return json
     */
    @Cached
    static String getUserFollowedStreamsHelix(Request request, Response response) {
        checkAuth(request);
        // Params
        String offset = request.queryParamOrDefault("offset", "0");
        String limit = request.queryParamOrDefault("limit", "20");
        String token = request.queryParams("token");
        if (token == null || token.isEmpty())
            throw halt(BAD_REQUEST, "Empty token");
        // Check cache
        String requestId = ApiCache.createKey("helix/user/follows/streams", offset, limit, token);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;
        // Calculate the from id from the token
        List<User> fromUsers = getUsers(null, null, token);
        if (fromUsers.size() == 0)
            throw halt(BAD_REQUEST, "User token invalid");
        String fromId = fromUsers.get(0).getId();
        // Get follows
        FollowList userFollows = getUserFollows(null, null, "100",
                fromId, null);
        if (userFollows == null || userFollows.getFollows() == null)
            throw halt(SERVER_ERROR, "Failed to connect to Twitch API");
        List<String> followIds = new ArrayList<>();
        for (Follow follow : userFollows.getFollows())
            if (follow.getToId() != null)
                followIds.add(follow.getToId());
        @NotNull List<com.rolandoislas.twitchunofficial.util.twitch.helix.Stream> streams =
                getStreams(getAfterFromOffset("0", "100"), null, null, "100",
                        null, null, null, followIds, null);
        // Cache and return
        String json = gson.toJson(streams);
        cache.set(requestId, json);
        return json;
    }

    /**
     * Gets user follows
     * @param after after cursor
     * @param before before cursor
     * @param first limit
     * @param fromId user id
     * @param toId user id
     * @return follow list
     */
    @NotCached
    @Nullable
    private static FollowList getUserFollows(@Nullable String after, @Nullable String before, @Nullable String first,
                                             @Nullable String fromId, @Nullable String toId) {
       if ((fromId == null || fromId.isEmpty()) && (toId == null || toId.isEmpty()))
           throw halt(BAD_REQUEST, "Missing to or from id");
        // Request live

        // Endpoint
        String requestUrl = String.format("%s/users/follows", API);
        RestTemplate restTemplate;
        if (twitchOauth != null)
            restTemplate = getPrivilegedRestTemplate(twitchOauth);
        else
            restTemplate = twitch.getRestClient().getRestTemplate();
        // Parameters
        if (after != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("after", after));
        if (before != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("before", before));
        if (first != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("first", first));
        if (fromId != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("from_id", fromId));
        if (toId != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("to_id", toId));
        // REST Request
        try {
            Logger.verbose( "Rest Request to [%s]", requestUrl);
            ResponseEntity<String> responseObject = restTemplate.exchange(requestUrl, HttpMethod.GET, null,
                    String.class);
            try {
                return gson.fromJson(responseObject.getBody(), FollowList.class);
            }
            catch (JsonSyntaxException e) {
                Logger.exception(e);
            }
        }
        catch (RestClientException | RestException e) {
            if (e instanceof RestException)
                Logger.warn("Request failed: " + ((RestException) e).getRestError().getMessage());
            else
                Logger.warn("Request failed: " + e.getMessage());
            Logger.exception(e);
        }
        return null;
    }

    /**
     * Get the Twitch after cursor value from the offset
     * @param offset page offset stating at zero
     * @param first page item limit
     * @return cursor(after)
     */
    @Nullable
    @NotCached
    private static String getAfterFromOffset(@Nullable String offset, String first) {
        if (offset != null) {
            try {
                long offsetLong = Long.parseLong(offset);
                long firstLong = Long.parseLong(first);
                Pagination pagination = new Pagination(
                        (offsetLong - 1) * firstLong,
                        (offsetLong + 1) * firstLong
                );
                return pagination.getCursor();
            }
            catch (NumberFormatException ignore) {}
        }
        return null;
    }

    /**
     * Get users that follow a user or users that a user follows...yeah
     * @param request request
     * @param response response
     * @return json
     */
    @Cached
    static String getUserFollowHelix(Request request, Response response) {
        checkAuth(request);
        // Params
        String after = request.queryParams("after");
        String before = request.queryParams("before");
        String first = request.queryParams("first");
        String fromId = request.queryParams("from_id");
        String toId = request.queryParams("to_id");
        // Non-spec params
        String offset = request.queryParams("offset");
        if (first == null)
            first = request.queryParamOrDefault("limit", "20");
        // Set after based on offset
        String afterFromOffset = getAfterFromOffset(offset, first);
        if (afterFromOffset != null)
            after = afterFromOffset;
        // Check cache
        String requestId = ApiCache.createKey("helix/user/follows", after, before, first, fromId, toId);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;
        // Get data
        FollowList followList = getUserFollows(after, before, first, fromId, toId);
        if (followList == null)
            throw halt(BAD_GATEWAY, "Bad Gateway: Could not connect to Twitch API");
        // Cache and return
        String json = gson.toJson(followList.getFollows());
        cache.set(requestId, json);
        return json;
    }

    /**
     * Search on the kraken endpoint
     * This combines games, channels, and streams search endpoints
     * Data returned is expected to be in "KRAKEN" game or community format json
     * @param request request
     * @param response response
     * @return json
     */
    @Cached
    static String getSearchKraken(Request request, Response response) {
        // All
        @Nullable String query = request.queryParams("query");
        String type = request.queryParamOrDefault("type", "streams");
        String limit = request.queryParamOrDefault("limit", "20");
        String offset = request.queryParamOrDefault("offset", "0");
        String hls = request.queryParamOrDefault("hls", "true");
        String live = request.queryParamOrDefault("live", "false");
        // Check params
        if (query == null || query.isEmpty())
            throw halt(BAD_REQUEST, "Empty query");
        long limitLong;
        try {
            limitLong = Long.parseLong(limit);
        }
        catch (NumberFormatException e) {
            throw halt(BAD_REQUEST, "Invalid limit");
        }
        // Check cache
        String requestId = ApiCache.createKey("kraken/search", query, type, limit, offset, hls, live);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;
        // Get live data
        String json;
        // Used by switch case
        ArrayList<String> userIds = new ArrayList<>();
        List<com.rolandoislas.twitchunofficial.util.twitch.helix.Stream> streamsHelix = new ArrayList<>();
        List<Game> games = new ArrayList<>();
        switch (type) {
            case "streams":
                // HLS param is ignored
                List<Stream> streams = twitch.getSearchEndpoint().getStreams(query, Optional.of(limitLong));
                for (Stream stream : streams)
                    userIds.add(String.valueOf(stream.getChannel().getId()));
                if (userIds.size() > 0)
                    streamsHelix = getStreams(
                            null,
                            null,
                            null,
                            "100",
                            null,
                            null,
                            null,
                            userIds,
                            null
                    );
                json = gson.toJson(streamsHelix);
                break;
            case "channels":
                // Get channels from search
                List<Channel> channels = twitch.getSearchEndpoint().getChannels(query, Optional.of(limitLong));
                // Get games
                List<String> gameNames = new ArrayList<>();
                for (Channel channel : channels)
                    gameNames.add(String.valueOf(channel.getGame()));
                games = getGames(null, gameNames);
                if (games == null)
                    throw halt(BAD_GATEWAY, "Failed to get games");
                // Get streams
                for (Channel channel : channels)
                    userIds.add(String.valueOf(channel.getId()));
                streamsHelix = getStreams(
                        null,
                        null,
                        null,
                        "100",
                        null,
                        null,
                        null,
                        userIds,
                        null
                );
                // Populate Streams
                SimpleDateFormat krakenDateFormat = new SimpleDateFormat("E MMM dd HH:mm:ss z yyyy");
                // ISO8601
                SimpleDateFormat hexlixDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                channelToStream:
                for (Channel channel : channels) {
                    // Check if stream is live
                    for (com.rolandoislas.twitchunofficial.util.twitch.helix.Stream stream : streamsHelix)
                        if (String.valueOf(stream.getUserId()).equals(String.valueOf(channel.getId())))
                            continue channelToStream;
                    // Add stream from channel data
                    com.rolandoislas.twitchunofficial.util.twitch.helix.Stream stream =
                            new com.rolandoislas.twitchunofficial.util.twitch.helix.Stream();
                    stream.setId("null"); // No stream id
                    stream.setUserId(String.valueOf(channel.getId()));
                    for (Game game : games)
                        if (game.getName() != null && game.getName().equalsIgnoreCase(channel.getGame()))
                            stream.setGameId(String.valueOf(game.getId()));
                    if (stream.getGameId() == null)
                        stream.setGameId("null");
                    stream.setCommunityIds(new ArrayList<>()); // No community ids
                    stream.setType("user"); // Set the type to user (This is not a valid Twitch API value)
                    stream.setTitle(String.valueOf(channel.getStatus()));
                    stream.setViewerCount(channel.getViews()); // No viewer count
                    // Converts the time string to ISO8601
                    String createdAt = "";
                    try {
                        Date krakenDate = krakenDateFormat.parse(String.valueOf(channel.getCreatedAt()));
                        createdAt =  hexlixDateFormat.format(krakenDate);
                    }
                    catch (ParseException e) {
                        e.printStackTrace();
                    }
                    stream.setStartedAt(createdAt);
                    stream.setLanguage(String.valueOf(channel.getLanguage()));
                    // This replaces Helix thumbnail size values, but the channel data has Kraken thumbnails.
                    // Helix data can be polled, but it would be another API call.
                    // At the moment the thumbnails are full size.
                    stream.setThumbnailUrl(String.valueOf(channel.getVideoBanner())
                            .replaceAll("\\d+x\\d+", "{width}x{height}"));
                    stream.setUserName(new UserName(String.valueOf(channel.getName()),
                            String.valueOf(channel.getDisplayName())));
                    stream.setGameName(String.valueOf(channel.getGame()));
                    streamsHelix.add(stream);
                }
                json = gson.toJson(streamsHelix);
                break;
            case "games":
                // Search
                List<me.philippheuer.twitch4j.model.Game> gamesKraken =
                        twitch.getSearchEndpoint().getGames(query, Optional.of(live.equals("true")));
                // Get games from the Helix endpoint
                List<String> gameIds = new ArrayList<>();
                if (gamesKraken != null)
                    for (me.philippheuer.twitch4j.model.Game game : gamesKraken)
                        gameIds.add(String.valueOf(game.getId()));
                if (gameIds.size() > 0)
                    games = getGames(gameIds, null);
                // Add viewers to helix data
                if (games != null && gamesKraken != null) {
                    for (Game game : games)
                        for (me.philippheuer.twitch4j.model.Game gameKraken : gamesKraken)
                            if (String.valueOf(game.getId()).equals(String.valueOf(gameKraken.getId())))
                                game.setViewers(gameKraken.getPopularity());
                    games.sort(new GameViewComparator().reversed());
                }
                json = gson.toJson(games);
                break;
            default:
                throw halt(BAD_REQUEST, "Invalid type");
        }
        // Cache and return
        cache.set(requestId, json);
        return json;
    }

    /**
     * Get top games from the Helix end point
     * @param request request
     * @param response response
     * @return json
     */
    @Cached
    static String getTopGamesHelix(Request request, Response response) {
        checkAuth(request);
        // Params
        String after = request.queryParams("after");
        String before = request.queryParams("before");
        String first = request.queryParams("first");
        // Non-spec params
        String offset = request.queryParams("offset");
        if (first == null)
            first = request.queryParamOrDefault("limit", "20");
        // Set after based on offset
        String afterFromOffset = getAfterFromOffset(offset, first);
        if (afterFromOffset != null)
            after = afterFromOffset;
        // Check cache
        String requestId = ApiCache.createKey("helix/games/top", after, before, first);
        String cachedResponse = cache.get(requestId);
        if (cachedResponse != null)
            return cachedResponse;

        // Fetch live data
        String requestUrl = String.format("%s/games/top", API);
        RestTemplate restTemplate = twitch.getRestClient().getRestTemplate();
        if (after != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("after", after));
        if (before != null)
            restTemplate.getInterceptors().add(new QueryRequestInterceptor("before", before));
        restTemplate.getInterceptors().add(new QueryRequestInterceptor("first", first));
        // REST Request
        List<Game> games = null;
        try {
            Logger.verbose( "Rest Request to [%s]", requestUrl);
            ResponseEntity<String> responseObject = restTemplate.exchange(requestUrl, HttpMethod.GET, null,
                    String.class);
            try {
                GameList gameList = gson.fromJson(responseObject.getBody(), GameList.class);
                games = gameList.getGames();
            }
            catch (JsonSyntaxException e) {
                Logger.exception(e);
            }
        }
        catch (RestClientException | RestException e) {
            Logger.warn("Request failed: " + e.getMessage());
            Logger.exception(e);
        }
        if (games == null)
            throw halt(BAD_GATEWAY, "Bad Gateway: Could not connect to Twitch API");
        // Add viewer info
        List<TopGame> gamesKraken = getTopGamesKraken("100", "0");
        if (gamesKraken != null) {
            for (Game game : games)
                for (TopGame gameKraken : gamesKraken)
                    if (String.valueOf(game.getId()).equals(
                            String.valueOf(gameKraken.getGame() != null ? gameKraken.getGame().getId() : null)))
                        game.setViewers(gameKraken.getViewers());
        }
        // Store and return
        String json = gson.toJson(games);
        cache.set(requestId, json);
        return json;
    }
}
