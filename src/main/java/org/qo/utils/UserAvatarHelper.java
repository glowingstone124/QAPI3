package org.qo.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.qo.server.AvatarCache;
import org.qo.services.loginService.AvatarRelatedImpl;
import org.qo.services.loginService.PlayerCardCustomizationImpl;
import reactor.core.publisher.Mono;

import static org.qo.utils.Logger.LogLevel.ERROR;

public final class UserAvatarHelper {
    private UserAvatarHelper() {}

    private record AvatarLookup(String url, String username) {}

    public static Mono<String> avatarTrans(
            String name,
            String publicBaseUrl,
            PlayerCardCustomizationImpl playerCardCustomizationImpl,
            AvatarRelatedImpl avatarRelatedImpl,
            Request request) {
        if (!AvatarCache.isValidName(name)) {
            return Mono.just(defaultAvatarJson(name));
        }
        return playerCardCustomizationImpl.getProfileDetailWithGivenNameReactive(name)
                .flatMap(profile -> {
                    if (profile.getAvatar() == null || "default".equals(profile.getAvatar())) {
                        return fetchMinecraftAvatar(name, publicBaseUrl, request);
                    }
                    return avatarRelatedImpl.getAvatarUrlReactive(profile.getAvatar())
                            .flatMap(url -> fetchSpecialAvatar(name, url, publicBaseUrl))
                            .switchIfEmpty(Mono.just(avatarJson(null, name, true)));
                })
                .switchIfEmpty(fetchMinecraftAvatar(name, publicBaseUrl, request))
                .onErrorResume(error -> Mono.just(defaultAvatarJson(name)));
    }

    public static Mono<String> fetchSpecialAvatar(String name, String url, String publicBaseUrl) {
        String cacheKey;
        try {
            cacheKey = AvatarCache.externalKey(url);
        } catch (IllegalArgumentException exception) {
            return Mono.just(avatarJson(url, name, true));
        }
        if (AvatarCache.isFreshKey(cacheKey)) {
            return Mono.just(avatarJson(AvatarCache.urlForKey(cacheKey, publicBaseUrl), name, true));
        }
        return Mono.fromFuture(AvatarCache.cacheAsyncForKey(url, cacheKey))
                .map(ignored -> avatarJson(AvatarCache.urlForKey(cacheKey, publicBaseUrl), name, true))
                .onErrorResume(error -> {
                    Logger.log("failed to cache special avatar for " + name + ": " + error.getMessage(), ERROR);
                    return Mono.just(avatarJson(url, name, true));
                });
    }

    public static Mono<String> fetchMinecraftAvatar(String name, String publicBaseUrl, Request request) {
        if (AvatarCache.isFresh(name)) {
            return Mono.just(avatarJson(AvatarCache.url(name, publicBaseUrl), name, false));
        }
        String apiURL = "https://api.mojang.com/users/profiles/minecraft/" + name;
        return Mono.fromFuture(request.sendGetRequest(apiURL))
                .map(JsonParser::parseString)
                .map(json -> json.getAsJsonObject().get("id").getAsString())
                .flatMap(uuid -> Mono.fromFuture(request.sendGetRequest("https://playerdb.co/api/player/minecraft/" + uuid))
                        .map(JsonParser::parseString)
                        .filter(JsonElement::isJsonObject)
                        .map(JsonElement::getAsJsonObject))
                .flatMap(playerDb -> {
                    if (!playerDb.get("success").getAsBoolean()) {
                        return Mono.just(defaultAvatarJson(name));
                    }
                    JsonObject player = playerDb.getAsJsonObject("data").getAsJsonObject("player");
                    String avatar = player.get("avatar").getAsString();
                    String username = player.get("username").getAsString();
                    AvatarLookup lookup = new AvatarLookup(avatar, username);
                    return Mono.fromFuture(AvatarCache.cacheAsync(lookup.url(), lookup.username()))
                        .map(ignored -> avatarJson(AvatarCache.url(lookup.username(), publicBaseUrl), lookup.username(), false))
                        .onErrorResume(error -> {
                            Logger.log("failed to cache avatar for " + lookup.username() + ": " + error.getMessage(), ERROR);
                            return Mono.just(avatarJson(lookup.url(), lookup.username(), false));
                        });
                })
                .onErrorResume(error -> Mono.just(defaultAvatarJson(name)));
    }

    public static String defaultAvatarJson(String name) {
        return avatarJson("https://crafthead.net/avatar/8667ba71b85a4004af54457a9734eed7", name, false);
    }

    public static String avatarJson(String url, String name, boolean special) {
        JsonObject result = new JsonObject();
        result.addProperty("url", url);
        result.addProperty("name", name);
        result.addProperty("special", special);
        return result.toString();
    }
}
