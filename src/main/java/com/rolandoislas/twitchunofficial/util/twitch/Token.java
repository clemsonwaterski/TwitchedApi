/*
 * Copyright (c) 2017 Rolando Islas. All Rights Reserved
 *
 */

package com.rolandoislas.twitchunofficial.util.twitch;

import com.google.gson.annotations.SerializedName;

public class Token {
    private String token;
    private String sig;
    @SerializedName("mobile_restricted")
    private boolean mobileRestricted;

    public String getToken() {
        return token;
    }

    public String getSig() {
        return sig;
    }
}
