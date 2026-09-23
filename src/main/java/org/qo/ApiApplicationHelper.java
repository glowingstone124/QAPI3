package org.qo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.qo.services.gameStatusService.Status;
import org.qo.services.registrationServices.MinecraftRegistrationSessionService;
import org.qo.services.registrationServices.RegistrationQuizProof;
import org.qo.services.registrationServices.RegistrationQuizService;
import org.qo.services.registrationServices.RegistrationVerificationMethod;
import org.qo.utils.ReturnInterface;
import org.qo.utils.UserProcess;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

import java.util.Locale;

public final class ApiApplicationHelper {
    private ApiApplicationHelper() {}

    public static String avatarPublicBaseUrl(ServerHttpRequest request) {
        String authority = request.getURI().getRawAuthority();
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String scheme = request.getURI().getScheme();
        String forwardedProto = request.getHeaders().getFirst("X-Forwarded-Proto");
        if (forwardedProto != null) {
            String candidate = forwardedProto.split(",", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (candidate.equals("http") || candidate.equals("https")) {
                scheme = candidate;
            }
        }
        return scheme + "://" + authority;
    }

    public static JsonObject buildGreetingJson(JsonObject timeJson, Status status) {
        JsonObject greetJson = new JsonObject();
        JsonArray onlines = new JsonArray();
        greetJson.add("time", timeJson);
        status.getStatusMap().forEach((id, info) -> {
            JsonArray singularUsers = new JsonArray();
            info.get("players").getAsJsonArray().forEach(elem -> singularUsers.add(elem.getAsJsonObject().get("name")));
            JsonObject serverPair = new JsonObject();
            serverPair.addProperty("id", id);
            serverPair.add("players", singularUsers);
            onlines.add(serverPair);
        });
        greetJson.add("online", onlines);
        return greetJson;
    }

    public static Mono<ResponseEntity<String>> handleRegistration(
            ApiApplication.RegisterRequest registration,
            ServerHttpRequest request,
            boolean chambersEnabled,
            MinecraftRegistrationSessionService sessionService,
            RegistrationQuizService quizService,
            UserProcess userProcess,
            ReturnInterface ri) {
        RegistrationVerificationMethod verificationMethod =
                RegistrationVerificationMethod.parse(registration.verificationMethod());
        if (verificationMethod == null) {
            return Mono.just(ri.failed("invalid verification method"));
        }
        if (verificationMethod == RegistrationVerificationMethod.MINECRAFT) {
            if (!chambersEnabled) {
                JsonObject response = new JsonObject();
                response.addProperty("code", "minecraft_verification_unavailable");
                response.addProperty("message", "Chamber 世界测试暂未开放。");
                return Mono.just(ri.GeneralHttpHeader(response.toString(), HttpStatus.SERVICE_UNAVAILABLE));
            }
            boolean verified = sessionService.consumePassed(
                    registration.verificationToken(),
                    registration.name(),
                    registration.uid()
            );
            if (!verified) {
                JsonObject response = new JsonObject();
                response.addProperty("code", "minecraft_verification_required");
                response.addProperty("message", "Minecraft 世界测试未通过、已过期或已使用。");
                return Mono.just(ri.GeneralHttpHeader(response.toString(), HttpStatus.FORBIDDEN));
            }
            return userProcess.regMinecraftUser(registration.name(), registration.uid(), request, registration.password(), 0);
        }
        RegistrationQuizProof proof = quizService.consumeProof(
                registration.verificationToken(),
                registration.name(),
                registration.uid()
        );
        if (proof == null) {
            JsonObject response = new JsonObject();
            response.addProperty("code", "quiz_verification_required");
            response.addProperty("message", "答题验证无效、已过期或已使用。");
            return Mono.just(ri.GeneralHttpHeader(response.toString(), HttpStatus.FORBIDDEN));
        }
        return userProcess.regMinecraftUser(registration.name(), registration.uid(), request, registration.password(), proof.getScore());
    }
}
