/*
 * Copyright (c) 2017 Rolando Islas. All Rights Reserved
 *
 */

package com.rolandoislas.twitchunofficial.util.twitch.helix;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

public class Game {
    private String id;
    private String name;
    @SerializedName("box_art_url")
    private String boxArtUrl;

    // Non-spec
    @Nullable private Long viewers;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setViewers(int viewers) {
        this.viewers = (long) viewers;
    }

    @Nullable
    public Long getViewers() {
        return viewers;
    }

    public long getViewersPrimitive() {
        return viewers != null ? viewers : 0;
    }
}
