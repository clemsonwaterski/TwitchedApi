/*
 * Copyright (c) 2017 Rolando Islas. All Rights Reserved
 *
 */

package com.rolandoislas.twitchunofficial;

import com.goebl.david.Webb;
import com.goebl.david.WebbException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.rolandoislas.twitchunofficial.data.json.LinkId;
import com.rolandoislas.twitchunofficial.data.json.CfVisitor;
import com.rolandoislas.twitchunofficial.data.annotation.Cached;
import com.rolandoislas.twitchunofficial.data.annotation.NotCached;
import com.rolandoislas.twitchunofficial.util.ApiCache;
import com.rolandoislas.twitchunofficial.util.HeaderUtil;
import com.rolandoislas.twitchunofficial.util.Logger;
import com.rolandoislas.twitchunofficial.data.json.LinkToken;
import com.rolandoislas.twitchunofficial.util.twitch.AccessToken;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.util.Random;
import java.util.stream.Collectors;

import static com.rolandoislas.twitchunofficial.TwitchUnofficial.cache;
import static com.rolandoislas.twitchunofficial.TwitchUnofficialApi.BAD_REQUEST;
import static com.rolandoislas.twitchunofficial.TwitchUnofficialApi.checkAuth;
import static com.rolandoislas.twitchunofficial.TwitchUnofficialApi.gson;
import static com.rolandoislas.twitchunofficial.TwitchUnofficialApi.halt;

class TwitchedApi {
    private static final String OAUTH_CALLBACK_PATH = "/link/complete";

    /**
     * Generate a new ID for a device to begin linking
     * @param request request
     * @param response response
     * @return json id
     */
    @Cached
    static String getLinkId(Request request, Response response) {
        checkAuth(request);
        String type = request.queryParams("type");
        String id = request.queryParams("id");
        // Check params
        if (type == null || !type.equals("roku"))
            throw halt(BAD_REQUEST, "Type is invalid");
        if (id == null || id.isEmpty())
            throw halt(BAD_REQUEST, "Id is empty");
        // Check cache
        String linkCacheId = ApiCache.createKey(ApiCache.LINK_PREFIX, type, id);
        cache.remove(linkCacheId);
        // Generate new ID
        String linkId;
        String usableChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        do {
            linkId = new Random().ints(6, 0, usableChars.length())
                    .mapToObj(i -> "" + usableChars.charAt(i))
                    .collect(Collectors.joining()).toUpperCase();
        }
        while (cache.containsLinkId(linkId));
        String shortLinkCacheId = ApiCache.createKey(ApiCache.LINK_PREFIX, linkId);
        // Store and return
        LinkId ret = new LinkId(linkId, getLinkIdVersionFromHeader(request));
        String retJson = gson.toJson(ret);
        cache.set(linkCacheId, retJson);
        cache.set(shortLinkCacheId, linkCacheId);
        return retJson;
    }

    /**
     * Get the version of link id to use depending on Twitched version header
     * @param request web request
     * @return version 1 (implicit) or 2 (authorization)
     */
    private static int getLinkIdVersionFromHeader(Request request) {
        @NotNull ComparableVersion version = HeaderUtil.extractVersion(request);
        return version.compareTo(new ComparableVersion("1.4")) >= 0 ? 2 : 1;
    }

    /**
     * Get the status of a link.
     * If the link has been completed (i.e. user logged in and there is a valid token) the completed field will be true
     * and the token field will be populated and present
     * @param request request
     * @param response reponse
     * @return link status json
     */
    @NotCached
    static String getLinkStatus(Request request, Response response) {
        checkAuth(request);
        String type = request.queryParams("type");
        String id = request.queryParams("id");
        // Check params
        if (type == null || !type.equals("roku"))
            throw halt(BAD_REQUEST, "Type is invalid");
        if (id == null || id.isEmpty())
            throw halt(BAD_REQUEST, "Id is empty");
        // Construct return json object
        JsonObject ret = new JsonObject();
        // Check cache for link id
        String linkCacheId = ApiCache.createKey(ApiCache.LINK_PREFIX, type, id);
        // Parse json
        LinkId linkId = new LinkId(cache.get(linkCacheId), gson);
        if (linkId.getLinkId() == null) {
            ret.addProperty("error", 500);
            return ret.toString();
        }
        // Check cache for token
        String tokenCacheKey = ApiCache.createKey(ApiCache.TOKEN_PREFIX, linkId.getLinkId());
        String token = cache.get(tokenCacheKey);
        if (token == null) {
            ret.addProperty("complete", false);
            return ret.toString();
        }
        AccessToken accessToken;
        try {
            accessToken = gson.fromJson(token, AccessToken.class);
        }
        catch (JsonSyntaxException e) {
            throw halt(500, "Server error: Invalid token json");
        }
        ret.addProperty("complete", true);
        ret.addProperty("token", accessToken.getAccessToken());
        ret.addProperty("refresh_token", accessToken.getRefreshToken());
        ret.addProperty("expires_in", accessToken.getExpiresIn());
        ret.addProperty("scope", accessToken.getScope());
        return ret.toString();
    }

    /**
     * Check if a link code is valid (e.g. waiting for a token)
     * @param linkId code to check
     * @return is valid
     */
    static boolean isLinkCodeValid(String linkId) {
        return cache.containsLinkId(linkId.toUpperCase());
    }

    /**
     * Redirect to the twitch Oauth endpoint
     * @param linkId id to use
     * @param request request
     * @param response response
     * @return string
     */
    static String redirectToTwitchOauth(String linkId, Request request, Response response) {
        // Get link id version
        String shortLinkCacheId = ApiCache.createKey(ApiCache.LINK_PREFIX, linkId.toUpperCase());
        String linkCacheId = cache.get(shortLinkCacheId);
        if (linkCacheId == null)
            throw Spark.halt(404, "Link id not found");
        LinkId linkIdObj = new LinkId(cache.get(linkCacheId), gson);
        if (linkIdObj.getLinkId() == null)
            throw Spark.halt(500, "Link id not found");
        String oauthUrl = "https://id.twitch.tv/oauth2/authorize?client_id=%s&redirect_uri=%s&response_type=%s" +
                "&scope=%s&force_verify=%s&state=%s";
        oauthUrl = String.format(
                oauthUrl, 
                TwitchUnofficialApi.twitch.getClientId(),
                getRedirectUrl(request),
                linkIdObj.getVersion() == 1 ? "token" : "code",
                "chat_login+user_follows_edit+user_subscriptions",
                "true",
                linkId.toUpperCase()
        );
        response.redirect(oauthUrl);
        return null;
    }

    /**
     * Get redirect url
     * @param request http request
     * @return redirect url
     */
    private static String getRedirectUrl(Request request) {
        String scheme = request.scheme();
        if (request.headers("X-Forwarded-Proto") != null)
            scheme = request.headers("X-Forwarded-Proto");
        if (request.headers("CF-Visitor") != null) {
            try {
                CfVisitor cfVisitor = gson.fromJson(request.headers("CF-Visitor"), CfVisitor.class);
                if (cfVisitor != null && cfVisitor.getScheme() != null)
                    scheme = cfVisitor.getScheme();
            }
            catch (JsonSyntaxException e) {
                Logger.exception(e);
            }
        }
        return scheme + "://" + request.host() + OAUTH_CALLBACK_PATH;
    }

    /**
     * Handle a post request for a token
     * @param request request
     * @param response response
     * @return empty string
     */
    static String postLinkToken(Request request, Response response) {
        // Parse body
        LinkToken linkToken;
        try {
            linkToken = gson.fromJson(request.body(), LinkToken.class);
        }
        catch (JsonSyntaxException e) {
            throw halt(BAD_REQUEST, "Invalid body");
        }
        String token = linkToken.getToken();
        String linkId = linkToken.getId();
        if (token == null || token.isEmpty())
            throw halt(BAD_REQUEST, "Invalid token");
        if (linkId == null || linkId.isEmpty() || !isLinkCodeValid(linkId))
            throw halt(BAD_REQUEST, "Invalid link id");
        linkId = linkId.toUpperCase();
        // Save token
        String tokenCacheKey = ApiCache.createKey(ApiCache.TOKEN_PREFIX, linkId);
        AccessToken accessToken = new AccessToken(token, null);
        cache.set(tokenCacheKey, gson.toJson(accessToken));
        // Return
        return "200";
    }

    /**
     * Request an access token from Twitch with an authorization code
     * @param request http request
     * @param authCode authentication code from Twitch
     * @param state linkId
     * @return if the token fetch was successful
     */
    public static boolean requestAccessToken(Request request, String authCode, String state) throws WebbException {
        if (!isLinkCodeValid(state.toUpperCase()))
            return false;
        String url = "https://id.twitch.tv/oauth2/token?client_id=%s&client_secret=%s&code=%s" +
                "&grant_type=%s&redirect_uri=%s";
        url = String.format(
                url,
                TwitchUnofficialApi.twitch.getClientId(),
                TwitchUnofficialApi.twitch.getClientSecret(),
                authCode,
                "authorization_code",
                getRedirectUrl(request)
        );
        com.goebl.david.Response<String> result;
        try {
            Webb webb = Webb.create();
            result = webb
                .post(url)
                .ensureSuccess()
                .asString();
        }
        catch (WebbException e) {
            Logger.exception(e);
            return false;
        }
        AccessToken accessToken;
        try {
            accessToken = gson.fromJson(result.getBody(), AccessToken.class);
        }
        catch (JsonSyntaxException e) {
            Logger.exception(e);
            return false;
        }
        String tokenCacheKey = ApiCache.createKey(ApiCache.TOKEN_PREFIX, state.toUpperCase());
        cache.set(tokenCacheKey, gson.toJson(accessToken));
        return true;
    }
}
