/*
 * Copyright (c) 2017 Rolando Islas. All Rights Reserved
 *
 */

package com.rolandoislas.twitchunofficial.util;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.heroku.sdk.EnvKeyStore;
import com.rolandoislas.twitchunofficial.data.model.CachedStreams;
import com.rolandoislas.twitchunofficial.data.model.FollowQueue;
import com.rolandoislas.twitchunofficial.data.model.Id;
import com.rolandoislas.twitchunofficial.data.model.json.twitch.helix.Stream;
import com.rolandoislas.twitchunofficial.data.model.json.twitch.helix.StreamUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.util.JedisURIHelper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ApiCache {
    private static final int TIMEOUT = 60 * 10; // Seconds before a cache value should be considered invalid
    public static final int TIMEOUT_HOUR = 60 * 60;
    public static final int TIMEOUT_DAY = 24 * 60 * 60; // 1 Day
    public static final int TIMEOUT_WEEK = TIMEOUT_DAY * 7; // week
    @SuppressWarnings("unused")
    private static final String USER_NAME_FIELD_PREFIX = "_u_";
    @SuppressWarnings("unused")
    private static final String GAME_NAME_FIELD_PREFIX = "_g_";
    public static final String LINK_PREFIX = "_l_";
    public static final String TOKEN_PREFIX = "_t_";
    private static final String FOLLOW_PREFIX = "_f_";
    private static final String FOLLOW_TIME_PREFIX = "_ft_";
    private static final String STREAM_PREFIX = "_s_";
    private static final String TOKEN_ID_PREFIX = "_ti_";
    private static final String USER_ID_PREFIX = "_ui_";
    private static final String FOLLOW_GAME_PREFIX = "_fg_";
    private static final String FOLLOW_TIME_GAME_PREFIX = "_ftg_";
    public static final String BIF_PREFIX = "_b_";
    private final String redisPassword;
    private final Gson gson;
    private JedisPool redisPool;

    public ApiCache(String redisServer) {
        String connectionLimit = System.getenv("REDIS_CONNECTIONS");
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setBlockWhenExhausted(true);
        if (connectionLimit != null && !connectionLimit.isEmpty())
            poolConfig.setMaxTotal((int) StringUtil.parseLong(connectionLimit));
        else
            poolConfig.setMaxTotal(1);
        // Create the pool
        boolean useSsl = Boolean.parseBoolean(System.getenv().getOrDefault("REDIS_SECURE", "true"));
        if (useSsl)
            redisServer = redisServer.replace("redis://", "rediss://");
        URI uri = URI.create(redisServer);
        if (JedisURIHelper.isValid(uri)) {
            String host = uri.getHost();
            int port = uri.getPort();
            redisPassword = JedisURIHelper.getPassword(uri);
            if (!useSsl) {
                Logger.warn("Connecting to Redis without SSL");
                redisPool = new JedisPool(poolConfig, host, port, Protocol.DEFAULT_TIMEOUT, redisPassword,
                        Protocol.DEFAULT_DATABASE, null);
            }
            else {
                try {
                    SSLSocketFactory sslSocketFactory = getSocketFactory();
                    SSLParameters sslParameters = new SSLParameters();
                    redisPool = new JedisPool(poolConfig, uri, sslSocketFactory, sslParameters, null);
                }
                catch (CertificateException | NoSuchAlgorithmException | KeyStoreException | IOException |
                        KeyManagementException e) {
                    Logger.exception(e);
                    redisPool = new JedisPool();
                }
            }
        }
        else {
            redisPool = new JedisPool();
            redisPassword = "";
        }
        gson = new Gson();
    }

    /**
     * Create a socket factory with the env var trusted cert
     * @return socket factory that trusts the env var
     */
    private SSLSocketFactory getSocketFactory() throws CertificateException, NoSuchAlgorithmException,
            KeyStoreException, IOException, KeyManagementException {
        String cert = System.getenv().getOrDefault("REDIS_TRUST", "")
                .replace("\\n", "\n");
        EnvKeyStore keyStore = EnvKeyStore.createFromPEMStrings(cert,
                new BigInteger(130, new SecureRandom()).toString(32));
        String algorithm = TrustManagerFactory.getDefaultAlgorithm();
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(algorithm);
        trustManagerFactory.init(keyStore.keyStore());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return sslContext.getSocketFactory();
    }

    /**
     * Get a value from redis
     * @param key key to get
     * @return value of key
     */
    @Nullable
    public String get(String key) {
        String value = null;
        try (Jedis redis = getAuthenticatedJedis()) {
            value = redis.get(key);
        } catch (Exception e) {
            Logger.exception(e);
        }
        return value;
    }

    public Jedis getAuthenticatedJedis() {
        Jedis jedis = redisPool.getResource();
        if (!redisPassword.isEmpty())
        jedis.auth(redisPassword);
        if (!jedis.isConnected())
            jedis.connect();
        return jedis;
    }

    /**
     * Create a key for an endpoint request with its parameters
     * @param endpoint API endpoint called
     * @param params parameters passed to endpoint
     * @return key
     */
    public static String createKey(String endpoint, Object... params) {
        StringBuilder key = new StringBuilder(endpoint);
        String separator = "?";
        for (Object param : params) {
            key.append(separator).append(String.valueOf(param));
            separator = "&";
        }
        return key.toString();
    }

    /**
     * @param key key to set
     * @param value value to set the key
     * @param timeout cache expire time in seconds
     */
    public void set(String key, String value, int timeout) {
        try (Jedis redis = getAuthenticatedJedis()) {
            redis.setex(key, timeout, value);
        } catch (Exception e) {
            Logger.exception(e);
        }
    }

    /**
     * Set a key with the default cache timeout
     * @see #set(String, String, int)
     */
    public void set(String key, String value) {
        set(key, value, TIMEOUT);
    }

    /**
     * Get user names from Redis.
     * Any user names that do not exist will be requested in a bulk request from twitch
     * @param ids ids to convert to user names
     * @return map with ids as keys and user names as values - user names not in cache will be null
     */
    public Map<String, String> getUserNames(List<String> ids) {
        return getNamesForIds(ids, Id.USER);
    }

    /**
     * Set user gson string
     * @param userNameIdMap id (key) - user gson (value)
     */
    public void setUsersJson(Map<String, String> userNameIdMap) {
        setUserOrGameJson(userNameIdMap, Id.USER);
    }

    /**
     * Get game names from Redis.
     * Any game names that do not exist will be requested in a bulk request from twitch
     * @param ids ids to convert to game names
     * @return map with ids as keys and game names as values - game names not in cache will be null
     */
    public Map<String, String> getGameNames(List<String> ids) {
        return getNamesForIds(ids, Id.GAME);
    }

    /**
     * Get game or id names from Redis.
     * Any names that do not exist will be requested in a bulk request from twitch
     * @param ids ids to convert to names
     * @return map with ids as keys and names as values - names not in cache will be null
     */
    private Map<String, String> getNamesForIds(List<String> ids, Id type) {
        // Determine key prefix
        String keyPrefix;
        switch (type) {
            case USER:
                keyPrefix = USER_NAME_FIELD_PREFIX;
                break;
            case GAME:
                keyPrefix = GAME_NAME_FIELD_PREFIX;
                break;
            default:
                throw new IllegalArgumentException("Type must be GAME or USER");
        }
        // Get ids stored on redis
        Map<String, String> cachedNames = mgetWithPrefix(keyPrefix, ids);
        // Map ids to names
        Map<String, String> nameIdMap = new HashMap<>();
        for (String id : ids) {
            String key = keyPrefix + id;
            nameIdMap.put(id, cachedNames.getOrDefault(key, null));
        }
        return nameIdMap;
    }

    /**
     * Get multiple keys each with a common prefix
     * @param keyPrefix prefix to be added to all keys
     * @param keys keys to get
     * @return map with redis keys as the key and possibly null value if key did not exist
     */
    private Map<String, String> mgetWithPrefix(String keyPrefix, List<String> keys) {
        Map<String, String> map = new HashMap<>();
        String[] prefixedKeys = new String[keys.size()];
        for (int keyIndex = 0; keyIndex < keys.size(); keyIndex++)
            prefixedKeys[keyIndex] = keyPrefix + String.valueOf(keys.get(keyIndex));
        List<String> values = new ArrayList<>();
        if (keys.size() > 0) {
            try (Jedis redis = getAuthenticatedJedis()) {
                values.addAll(redis.mget(prefixedKeys));
            } catch (Exception e) {
                Logger.exception(e);
            }
        }
        if (values.size() != prefixedKeys.length)
            for (String key : prefixedKeys)
                map.put(key, null);
        else
            for (int keyIndex = 0; keyIndex < prefixedKeys.length; keyIndex++)
                map.put(prefixedKeys[keyIndex], values.get(keyIndex));
        return map;
    }

    /**
     * Set user names
     * @param gameNames id (key) - user name (value)
     */
    public void setGamesJson(Map<String, String> gameNames) {
        setUserOrGameJson(gameNames, Id.GAME);
    }

    /**
     * Store user or game json
     * @param jsonMap <id, json>
     */
    private void setUserOrGameJson(Map<String, String> jsonMap, Id type) {
        // Determine key prefix
        String keyPrefix;
        int keyTimeout;
        switch (type) {
            case USER:
                keyPrefix = USER_NAME_FIELD_PREFIX;
                keyTimeout = TIMEOUT_DAY;
                break;
            case GAME:
                keyPrefix = GAME_NAME_FIELD_PREFIX;
                keyTimeout = TIMEOUT_DAY;
                break;
            default:
                throw new IllegalArgumentException("Type must be GAME or USER");
        }
        try (Jedis redis = getAuthenticatedJedis()) {
            for (Map.Entry<String, String> idJson : jsonMap.entrySet()) {
                if (idJson.getKey() == null || idJson.getValue() == null)
                    continue;
                String key = keyPrefix + idJson.getKey();
                long result = redis.setnx(key, idJson.getValue());
                if (result == 1)
                    redis.expire(key, keyTimeout);
            }
        } catch (Exception e) {
            Logger.exception(e);
        }
    }

    /**
     * Attempt to remove a key
     * Fails silently
     * @param key key to remove
     */
    @SuppressWarnings("UnusedReturnValue")
    public Long remove(String key) {
        long ret = 0L;
        try (Jedis redis = getAuthenticatedJedis()) {
            ret = redis.del(key);
        } catch (Exception e) {
            Logger.exception(e);
        }
        return ret;
    }

    /**
     * Check if a link id exists
     * @param linkId id to check for
     * @return id exists in cache
     */
    public boolean containsLinkId(String linkId) {
        String linkCacheId = ApiCache.createKey(ApiCache.LINK_PREFIX, linkId);
        String tokenCacheId = ApiCache.createKey(ApiCache.TOKEN_PREFIX, linkId);
        List<String> ids = new ArrayList<>();
        ids.add(linkCacheId);
        ids.add(tokenCacheId);
        Map<String, String> keys = mget(ids);
        for (String value : keys.values())
            if (value != null)
                return true;
        return false;
    }

    /**
     * Wrapper for a fail-safe mget
     * Gets multiple values
     * @param keys keys to fetch
     * @return map of all keys with potential null values if the key does not exist in cache
     */
    private Map<String, String> mget(List<String> keys) {
        return mgetWithPrefix("", keys);
    }

    /**
     * Get cached follows for an id
     * @param fromId id to get follows for
     * @return followed ids
     */
    public List<String> getFollows(String fromId) {
        List<String> followsList = new ArrayList<>();
        try (Jedis redis = getAuthenticatedJedis()) {
            Set<String> follows = redis.smembers(FOLLOW_PREFIX + fromId);
            followsList.addAll(follows);
        } catch (Exception e) {
            Logger.exception(e);
        }
        return followsList;
    }

    /**
     * Set follows for a user id
     * @param fromId user to set follows for
     * @param toIds id the user follows
     */
    void setFollows(String fromId, List<String> toIds) {
        // Remove old follows
        List<String> follows = getFollows(fromId);
        try (Jedis redis = getAuthenticatedJedis()) {
            if (follows.size() > 0)
                redis.srem(FOLLOW_PREFIX + fromId, follows.toArray(new String[0]));
        } catch (Exception e) {
            Logger.exception(e);
        }
        // Set follows
        setFollowInSet(fromId, toIds, FOLLOW_PREFIX, FollowQueue.FollowType.CHANNEL);
    }

    /**
     * Get the time that a follows set was set
     * @param fromId id
     * @return last set time of follows set for an id
     */
    public long getFollowIdCacheTime(String fromId, FollowQueue.FollowType followType) {
        String prefix = "";
        switch (followType) {
            case CHANNEL:
                prefix = FOLLOW_TIME_PREFIX;
                break;
            case GAME:
                prefix = FOLLOW_TIME_GAME_PREFIX;
                break;
        }
        long time = 0;
        try (Jedis redis = getAuthenticatedJedis()) {
            String timeString = redis.get(prefix + fromId);
            if (timeString != null && !timeString.isEmpty()) {
                try {
                    time =  Long.parseLong(timeString);
                }
                catch (NumberFormatException ignore) {}
            }
        } catch (Exception e) {
            Logger.exception(e);
        }
        return time;
    }

    /**
     * Caches a list of streams
     * @param streams streams to cache
     */
    public void cacheStreams(List<Stream> streams) {
        try (Jedis redis = getAuthenticatedJedis()) {
            for (Stream stream : streams) {
                if (stream == null || ((stream.getUserId() == null || stream.getUserName() == null ||
                        stream.getUserName().getLogin() == null) && stream.isOnline()))
                    continue;
                if (stream.getUserId() != null && stream.getUserId().equals("168843586") && stream.getTitle() != null
                        && stream.getTitle().toUpperCase().contains("NFL")) {
                    stream.setOnline(false);
                    stream.setTitle("DRM Stream | " + stream.getTitle());
                    stream.setType("DRM Stream");
                }
                String json = gson.toJson(stream);
                String id = String.format("%s%s", STREAM_PREFIX, stream.getUserId());
                redis.setex(id, TIMEOUT, json);
            }
        }
        catch (Exception e) {
            Logger.exception(e);
        }
    }

    /**
     * Get streams from the cache
     * @param userIds optional ids to look for
     * @return cached streams object containing any missing ids/login and all found streams
     */
    @NotNull
    public CachedStreams getStreams(@NotNull List<String> userIds) {
        CachedStreams cachedStreams = new CachedStreams();
        List<Stream> offlineStreams = new ArrayList<>();
        // Find matching streams
        Map<String, String> streams = mgetWithPrefix(STREAM_PREFIX, userIds);
        for (Map.Entry<String, String> streamEntry : streams.entrySet()) {
            try {
                Stream stream = gson.fromJson(streamEntry.getValue(), Stream.class);
                if (stream != null && !stream.isOnline()) {
                    offlineStreams.add(stream);
                    continue;
                }
                if (stream == null || stream.getUserId() == null || stream.getUserName() == null ||
                        stream.getUserName().getLogin() == null ||
                        stream.getUserName().getLogin().isEmpty())
                    continue;
                if (userIds.contains(stream.getUserId()))
                    cachedStreams.getStreams().add(stream);
            }
            catch (JsonSyntaxException e) {
                Logger.exception(e);
            }
        }
        // Populate missing lists
        for (String id : userIds)
            if (!StreamUtil.streamListContainsId(cachedStreams.getStreams(), id) &&
                    !StreamUtil.streamListContainsId(offlineStreams, id))
                cachedStreams.getMissingIds().add(id);
        return cachedStreams;
    }

    /**
     * Cache a SHA1 salted token with its corresponding user id
     * @param id user id
     * @param token RAW TOKEN!
     */
    public void cacheUserIdFromToken(String id, String token) {
        String tokenHash = AuthUtil.hashString(token, null);
        try (Jedis redis = getAuthenticatedJedis()) {
            redis.setex(TOKEN_ID_PREFIX + tokenHash, TIMEOUT_DAY, id);
        }
        catch (Exception e) {
            Logger.exception(e);
        }
    }

    /**
     * Get cached user id from a user access token token
     * @param token RAW TOKEN!
     * @return id or null if not in cache
     */
    @Nullable
    public String getUserIdFromToken(String token) {
        String tokenHash = AuthUtil.hashString(token, null);
        return get(TOKEN_ID_PREFIX + tokenHash);
    }

    /**
     * Fetch ids stored in the Redis cache
     * @param logins logins to search for
     * @return map with values as nulls if not found
     */
    public Map<String, String> getUserIds(List<String> logins) {
        Map<String, String> cachedIds = mgetWithPrefix(USER_ID_PREFIX, logins);
        Map<String, String> retIds = new HashMap<>();
        for (String login : logins) {
            if (login == null || login.isEmpty())
                continue;
            retIds.put(login, cachedIds.getOrDefault(USER_ID_PREFIX + login, null));
        }
        return retIds;
    }

    /**
     * Set a map of user logins and ids
     * @param loginsIds map of logins and ids
     */
    public void setUserIds(Map<String, String> loginsIds) {
        try (Jedis redis = getAuthenticatedJedis()) {
            for (Map.Entry<String, String> loginId : loginsIds.entrySet()) {
                if (loginId.getKey() == null || loginId.getKey().isEmpty() || loginId.getValue() == null ||
                        loginId.getValue().isEmpty())
                    continue;
                String key = USER_ID_PREFIX + loginId.getKey();
                redis.setex(key, TIMEOUT_DAY, loginId.getValue());
            }
        }
        catch (Exception e) {
            Logger.exception(e);
        }
    }

    /**
     * Get followed games from the cache
     * @param userName Twitch user login name
     * @return list of game ids the user is following
     */
    public List<String> getFollowedGames(String userName) {
        List<String> followsList = new ArrayList<>();
        try (Jedis redis = getAuthenticatedJedis()) {
            Set<String> follows = redis.smembers(FOLLOW_GAME_PREFIX + userName);
            followsList.addAll(follows);
        }
        catch (Exception e) {
            Logger.exception(e);
        }
        return followsList;
    }

    /**
     * Set followed game ids for a user
     * @param userName Twitch user name
     * @param followedGameIds followed game ids
     */
    void setFollowedGames(String userName, List<String> followedGameIds) {
        // Remove old follows
        List<String> follows = getFollowedGames(userName);
        try (Jedis redis = getAuthenticatedJedis()) {
            if (follows.size() > 0)
                redis.srem(FOLLOW_GAME_PREFIX + userName, follows.toArray(new String[0]));
        }
        catch (Exception e) {
            Logger.exception(e);
        }
        // Set follows
        setFollowInSet(userName, followedGameIds, FOLLOW_GAME_PREFIX, FollowQueue.FollowType.GAME);
    }

    /**
     * Set follows id in a set
     * @param id set id
     * @param toIds ids to set the set to
     * @param prefix set prefix
     */
    private void setFollowInSet(String id, List<String> toIds, String prefix, FollowQueue.FollowType followType) {
        String timePrefix = "";
        switch (followType) {
            case CHANNEL:
                timePrefix = FOLLOW_TIME_PREFIX;
                break;
            case GAME:
                timePrefix = FOLLOW_TIME_GAME_PREFIX;
                break;
        }
        try (Jedis redis = getAuthenticatedJedis()) {
            if (toIds.size() > 0) {
                redis.sadd(prefix + id, toIds.toArray(new String[0]));
                redis.expire(prefix + id, TIMEOUT_DAY);
            }
            redis.setex(timePrefix + id, TIMEOUT_HOUR, String.valueOf(System.currentTimeMillis()));
        }
        catch (Exception e) {
            Logger.exception(e);
        }
    }
}
