package com.botstation.features;

import com.botstation.core.BotPaths;
import org.json.JSONArray;
import org.json.JSONObject;

/** Locks down complete mobile pagination and all song nickname fields. */
public final class MobileDataServiceRegressionTest {
    private MobileDataServiceRegressionTest() {}

    public static void main(String[] args) throws Exception {
        MobileDataService service = new MobileDataService(BotPaths.detect());
        JSONObject payload = service.songs("", 0, 200);
        JSONArray items = payload.getJSONArray("items");
        require(items.length() > 100, "mobile song data is still capped at 100 rows");
        require(payload.getInt("total") > items.length(), "song total is not the full matching count");
        JSONObject secondPage = service.songs("", payload.getInt("nextOffset"), 200);
        require(secondPage.getInt("offset") == items.length(), "second song page starts at the wrong offset");
        JSONObject first = items.getJSONObject(0);
        require(first.has("song_nickname2"), "song_nickname2 is missing from mobile data");
        require(first.has("song_nickname3"), "song_nickname3 is missing from mobile data");
        require(first.has("artist_nickname"), "artist_nickname is missing from mobile data");
        require(first.has("4k_ez") && first.has("6k_mx"), "difficulty columns are missing from mobile data");
        System.out.println("MOBILE_DATA_GREEN");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
