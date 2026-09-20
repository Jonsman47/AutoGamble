package com.jonsman.autogamble.targeting;

import java.net.http.HttpClient;

public interface LeaderboardProvider {
    String id();
    LeaderboardSnapshot fetch(HttpClient client) throws Exception;
}
