package org.wordpress.android.fluxc.network.rest.wpcom.theme;

import java.util.List;
import java.util.Map;

public class ThemeWPComResponse {
    public class MultipleWPComThemesResponse {
        public Map<String, ThemeWPComResponse> themes;
        public int count;
    }

    public class ThemeArrayResponse {
        public List<ThemeWPComResponse> themes;
    }

    public class Price {
        public int value;
        public String currency;
        public String display;
    }

    public String id;
    public String slug;
    public String stylesheet;
    public String name;
    public String author;
    public String author_uri;
    public String theme_uri;
    public String demo_uri;
    public String version;
    public String template;
    public String screenshot;
    public String description;
    public String date_launched;
    public String date_updated;
    public String language;
    public String download_uri;
    public Price price;
    public int rank_popularity;
    public int rank_trending;
}